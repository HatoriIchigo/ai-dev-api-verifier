#!/usr/bin/env bash
#
# verify.sh
#
# 「決定された技術スタック」を表す JSON ファイルのキー構造を検証するスクリプト。
# 値の内容（言語の種別・バージョン書式など）は問わず、**キーがそろっているか**だけを検証する。
# jq が未インストールの場合は自動でインストールを試みる。
#
# 期待するスキーマ（キーのみ。値の型・内容は本検証の対象外）:
#   {
#     "language": { "kind": ..., "version": ... },
#     "package":  ...,
#     "build":    ...,
#     "libraries": [ { "name": ..., "version": ... }, ... ]
#   }
#
# キー検証は「不足キー」「未知キー」の双方をエラーとする（キー集合の完全一致）。
#
# さらに language.kind と package / build の組み合わせ妥当性を**助言として**確認する
# （java->maven/gradle, python->pip, typescript->npm）。組み合わせは強制せず、非許可ツール・
# 未知言語でもエラーにはしない（終了コードはキー検証のみが左右する）。
#
# 使い方:
#   ./verify.sh <input.json>
#
# 終了コード:
#   0 … 検証成功
#   1 … 検証失敗（不足キー／未知キー／構造不正）・入力エラー
#
set -euo pipefail

# ---------------------------------------------------------------------------
# ログ出力
# ---------------------------------------------------------------------------
info()  { printf '\033[36m[INFO]\033[0m  %s\n' "$*"; }
warn()  { printf '\033[33m[WARN]\033[0m  %s\n' "$*" >&2; }
error() { printf '\033[31m[ERROR]\033[0m %s\n' "$*" >&2; }

# ---------------------------------------------------------------------------
# jq の存在確認とインストール
# ---------------------------------------------------------------------------
install_jq() {
  info "jq が見つかりません。インストールを試みます..."

  # root 以外、かつ sudo がある場合のみ sudo を付与する
  local SUDO=""
  if [[ "$(id -u)" -ne 0 ]] && command -v sudo >/dev/null 2>&1; then
    SUDO="sudo"
  fi

  # OS / パッケージマネージャを判定してインストール
  if command -v apt-get >/dev/null 2>&1; then
    $SUDO apt-get update && $SUDO apt-get install -y jq
  elif command -v dnf >/dev/null 2>&1; then
    $SUDO dnf install -y jq
  elif command -v yum >/dev/null 2>&1; then
    $SUDO yum install -y jq
  elif command -v apk >/dev/null 2>&1; then
    $SUDO apk add --no-cache jq
  elif command -v pacman >/dev/null 2>&1; then
    $SUDO pacman -Sy --noconfirm jq
  elif command -v zypper >/dev/null 2>&1; then
    $SUDO zypper install -y jq
  elif command -v brew >/dev/null 2>&1; then
    brew install jq
  else
    error "対応するパッケージマネージャが見つかりませんでした。手動で jq をインストールしてください: https://jqlang.github.io/jq/"
    exit 1
  fi
}

ensure_jq() {
  if command -v jq >/dev/null 2>&1; then
    return 0
  fi

  install_jq

  if ! command -v jq >/dev/null 2>&1; then
    error "jq のインストールに失敗しました。"
    exit 1
  fi
  info "jq のインストールが完了しました (version: $(jq --version))"
}

# ---------------------------------------------------------------------------
# JSON 構文の検証
# ---------------------------------------------------------------------------
verify_json() {
  local file="$1"

  if [[ ! -f "$file" ]]; then
    error "ファイルが存在しません: $file"
    exit 1
  fi

  if ! jq empty "$file" >/dev/null 2>&1; then
    error "JSON の構文が不正です: $file"
    jq empty "$file" || true
    exit 1
  fi

  info "JSON 構文 OK: $file"
}

# ---------------------------------------------------------------------------
# キー検証
#   - トップレベル必須キー: language / package / build / libraries
#   - language オブジェクト必須キー: kind / version
#   - libraries は配列。各要素は name / version を持つオブジェクト
#   - 不足キー・未知キー（キー集合の不一致）はいずれもエラー
#   値の型・内容は検証しない（キーがそろっているかのみ）。
# ---------------------------------------------------------------------------
verify_keys() {
  local file="$1"
  local errors

  errors="$(jq -r '
    . as $root

    # 期待キー集合
    | ["language", "package", "build", "libraries"] as $topKeys
    | ["kind", "version"]    as $langKeys
    | ["name", "version"]    as $libKeys

    | [
        # --- トップレベル: 不足キー ---
        ( $topKeys[] as $k
          | select( ($root | has($k)) | not )
          | "トップレベルに必須キーがありません: \($k)" ),

        # --- トップレベル: 未知キー ---
        ( ($root | keys_unsorted[]) as $k
          | select( ($topKeys | index($k)) | not )
          | "トップレベルに未知のキーがあります: \($k)" ),

        # --- language: 型 ---
        ( select($root | has("language"))
          | select( ($root.language | type) != "object" )
          | "language はオブジェクトである必要があります（実際: \($root.language | type)）" ),

        # --- language: 不足キー ---
        ( select( ($root | has("language")) and (($root.language | type) == "object") )
          | $langKeys[] as $k
          | select( ($root.language | has($k)) | not )
          | "language に必須キーがありません: \($k)" ),

        # --- language: 未知キー ---
        ( select( ($root | has("language")) and (($root.language | type) == "object") )
          | ($root.language | keys_unsorted[]) as $k
          | select( ($langKeys | index($k)) | not )
          | "language に未知のキーがあります: \($k)" ),

        # --- libraries: 型 ---
        ( select($root | has("libraries"))
          | select( ($root.libraries | type) != "array" )
          | "libraries は配列である必要があります（実際: \($root.libraries | type)）" ),

        # --- libraries[]: 要素の型 ---
        ( select( ($root | has("libraries")) and (($root.libraries | type) == "array") )
          | $root.libraries | to_entries[] | .key as $i | .value as $lib
          | select( ($lib | type) != "object" )
          | "libraries[\($i)] はオブジェクトである必要があります（実際: \($lib | type)）" ),

        # --- libraries[]: 不足キー ---
        ( select( ($root | has("libraries")) and (($root.libraries | type) == "array") )
          | $root.libraries | to_entries[] | .key as $i | .value as $lib
          | select( ($lib | type) == "object" )
          | $libKeys[] as $k
          | select( ($lib | has($k)) | not )
          | "libraries[\($i)] に必須キーがありません: \($k)" ),

        # --- libraries[]: 未知キー ---
        ( select( ($root | has("libraries")) and (($root.libraries | type) == "array") )
          | $root.libraries | to_entries[] | .key as $i | .value as $lib
          | select( ($lib | type) == "object" )
          | ($lib | keys_unsorted[]) as $k
          | select( ($libKeys | index($k)) | not )
          | "libraries[\($i)] に未知のキーがあります: \($k)" )
      ]
    | .[]
  ' "$file")"

  if [[ -n "$errors" ]]; then
    while IFS= read -r line; do
      [[ -z "$line" ]] && continue
      error "$line"
    done <<< "$errors"
    exit 1
  fi

  info "キー検証 OK: $file"
}

# ---------------------------------------------------------------------------
# 構成妥当性チェック（助言。終了コードには影響しない＝常に成功扱い）
#   language.kind ごとの許可ツール（package / build の双方に適用）:
#     java       -> maven / gradle
#     python     -> pip
#     typescript -> npm
#   組み合わせは強制しない（非許可ツール・未知言語でもエラーにはしない）。
#   既知の妥当な構成を認識して知らせることが目的で、CI ゲートはキー検証が担う。
# ---------------------------------------------------------------------------
verify_combo() {
  local file="$1"
  local status tag rest

  status="$(jq -r '
    {
      "java":       ["maven", "gradle", "ant"],
      "python":     ["pip"],
      "typescript": ["npm"],
      "go":         ["go", "gomod"],
      "rust":       ["cargo"]
    } as $matrix
    | (.language.kind) as $kind
    | (.package) as $pkg
    | (.build)   as $bld
    | ($kind | if type == "string" then . else tojson end) as $kstr
    | if ($matrix | has($kstr)) then
        $matrix[$kstr] as $allowed
        | if (($allowed | index($pkg)) != null) and (($allowed | index($bld)) != null)
          then "OK\t\($kstr) (package=\($pkg), build=\($bld))"
          else "MISMATCH\t\($kstr) package=\($pkg) build=\($bld)（許可: \($allowed | join("/"))）"
          end
      else
        "SKIP\t\($kstr)（マトリクス対象外）"
      end
  ' "$file")"

  tag="${status%%$'\t'*}"
  rest="${status#*$'\t'}"

  case "$tag" in
    OK)
      info "構成は妥当です（既知の構成）: $rest" ;;
    SKIP)
      info "構成妥当性チェックは対象外です（言語がマトリクスに無いため許容）: $rest" ;;
    MISMATCH)
      info "既知の妥当な組み合わせに一致しません（許容・非エラー）: $rest" ;;
  esac
}

# ---------------------------------------------------------------------------
# メイン
# ---------------------------------------------------------------------------
main() {
  if [[ $# -lt 1 ]]; then
    error "使い方: $0 <input.json>"
    exit 1
  fi

  local file="$1"

  ensure_jq
  verify_json "$file"
  verify_keys "$file"
  verify_combo "$file"

  info "検証が完了しました。"
}

main "$@"
