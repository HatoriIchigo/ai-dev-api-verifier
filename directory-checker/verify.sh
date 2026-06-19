#!/usr/bin/env bash
#
# verify.sh
#
# プロジェクトのディレクトリ／ファイル構成を検証するエントリスクリプト。
# 「設計 JSON」ではなく**実ファイルツリー**を対象とし、レイヤード構成の配置・命名・連番などを検証する
# （旧 java-builder のディレクトリ/ファイル構成検証を移行したもの。コード内容＝AST 検査は java-builder 側に残る）。
#
# 使い方:
#   ./verify.sh --dir <検証対象ディレクトリ> --type <iac|backend|frontend>
#
# 引数:
#   --dir  <dir>   検証対象プロジェクトのルートディレクトリ（必須）
#   --type <type>  プロジェクト種別。iac / backend / frontend のいずれか（必須）。
#                  これ以外の値はエラー終了する。
#
# 種別ごとの扱い（今回は Java backend の移行のため backend のみ実装）:
#   backend  … ビルドファイル（pom.xml / build.gradle 等）を検出したら Java backend として
#              backend/java/verify.sh で構成検証する。Java と判定できなければ未対応としてスキップ（pass）。
#   iac      … 未実装。未対応としてスキップ（pass）。
#   frontend … 未実装。未対応としてスキップ（pass）。
#
# 終了コード:
#   0 … 検証成功（または未対応スキップ）
#   1 … 検証失敗（構成違反）・入力エラー（引数不備・不正な type・存在しないディレクトリ）
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---------------------------------------------------------------------------
# ログ出力
# ---------------------------------------------------------------------------
info()  { printf '\033[36m[INFO]\033[0m  %s\n' "$*"; }
warn()  { printf '\033[33m[WARN]\033[0m  %s\n' "$*" >&2; }
error() { printf '\033[31m[ERROR]\033[0m %s\n' "$*" >&2; }

usage() {
  cat >&2 <<'EOF'
使い方: verify.sh --dir <検証対象ディレクトリ> --type <iac|backend|frontend>
  --dir  <dir>   検証対象プロジェクトのルートディレクトリ（必須）
  --type <type>  iac | backend | frontend のいずれか（必須。これ以外はエラー）
EOF
}

# ---------------------------------------------------------------------------
# Java プロジェクト判定（ビルドファイルの有無で判断）
# ---------------------------------------------------------------------------
is_java_project() {
  local dir="$1"
  [[ -f "$dir/pom.xml" \
     || -f "$dir/build.gradle" || -f "$dir/build.gradle.kts" \
     || -f "$dir/settings.gradle" || -f "$dir/settings.gradle.kts" ]]
}

# ---------------------------------------------------------------------------
# backend ディスパッチ
#   ビルドファイルを検出したら Java backend として構成検証する。
#   検出できなければ未対応（Java 以外の backend）としてスキップ（pass）。
# ---------------------------------------------------------------------------
run_backend() {
  local dir="$1"
  if is_java_project "$dir"; then
    info "Java backend を検出しました（ビルドファイルあり）。構成検証を実行します: $dir"
    # backend/java/verify.sh は違反時に非ゼロ終了する（set -e で本スクリプトへ伝播）。
    "$SCRIPT_DIR/backend/java/verify.sh" "$dir"
  else
    info "Java のビルドファイル（pom.xml / build.gradle 等）が見つかりません。backend/java 以外は未対応のためスキップします（pass）。"
  fi
}

# ---------------------------------------------------------------------------
# メイン
# ---------------------------------------------------------------------------
main() {
  local dir="" type=""

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --dir)
        [[ $# -ge 2 ]] || { error "--dir に値が指定されていません"; usage; exit 1; }
        dir="$2"; shift 2 ;;
      --dir=*)  dir="${1#--dir=}";  shift ;;
      --type)
        [[ $# -ge 2 ]] || { error "--type に値が指定されていません"; usage; exit 1; }
        type="$2"; shift 2 ;;
      --type=*) type="${1#--type=}"; shift ;;
      -h|--help) usage; exit 0 ;;
      *) error "不明な引数: $1"; usage; exit 1 ;;
    esac
  done

  # --dir 検証
  if [[ -z "$dir" ]]; then
    error "--dir は必須です"; usage; exit 1
  fi
  if [[ ! -d "$dir" ]]; then
    error "ディレクトリが存在しません: $dir"; exit 1
  fi

  # --type 検証（iac|backend|frontend 以外はエラー終了）
  case "$type" in
    iac|backend|frontend) ;;
    "") error "--type は必須です（iac|backend|frontend のいずれか）"; usage; exit 1 ;;
    *)  error "--type が不正です: '$type'（iac|backend|frontend のいずれかを指定してください）"; exit 1 ;;
  esac

  # 種別ディスパッチ
  case "$type" in
    backend)
      run_backend "$dir" ;;
    iac|frontend)
      info "type '$type' は未対応のため検証をスキップします（現状 backend/java のみ実装）。" ;;
  esac

  info "検証が完了しました。"
}

main "$@"
