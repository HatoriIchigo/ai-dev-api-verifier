#!/usr/bin/env bash
#
# backend/java/verify.sh
#
# Java backend プロジェクトの**ディレクトリ／ファイル構成**を検証する（旧 java-builder の
# DirectoryValidator・AppStructureValidator・IntegrationTestValidator のルール17＝構造部分を移行）。
# コード内容（AST 検査：文字列ハードコード／if-for 禁止／命名 import 解決など）は java-builder 側に残す。
#
# 検証ルール（正本: ../../docs/code-rule.md「ディレクトリ構成ルール」）:
#   - D1 ルート構成: <src>/main/java/com/<projectName>/app/ 構成であること（projectName は ^[0-9a-zA-Z_-]+$）
#   - ファイル種別: <src>/{main,test}/java は .java のみ、<src>/{main,test}/resources は .yaml/.yml のみ
#   - D2 app/ 配下配置: app 直下は Application.java のみ。許可ディレクトリ
#       （top/internal/layer<N>/repository/dto/in/dto/out/log/util/validation/constants）以外に .java を置けない
#   - D3 dto/in と dto/out の .java 件数一致
#   - D4 layer<数値> は1始まりの連番（歯抜け不可）
#   - D5 命名サフィックス: repository=*Repository / dto/in=*InDto / dto/out=*OutDto
#   - ルール17（構造部分）: <src>/test/java/com/<projectName>/integration/ が存在し .java が1つ以上
#
# ソースルート（<src>）は固定 "src" ではなく解決する（java-builder と整合）。優先順位:
#   1. 環境変数 JAVA_BUILDER_SRC_ROOT
#   2. <dir>/application.yaml（または application.yml）の top-level キー src-root
#   3. 既定値 "src"
#   ※ application.yaml の読み取りは top-level の単一スカラ `src-root: <値>` のみ対応（簡易パーサ）。
#
# 使い方:
#   backend/java/verify.sh <検証対象プロジェクトのルート>
#
# 終了コード: 0=構成OK / 1=構成違反・入力エラー
#
set -euo pipefail

info()  { printf '\033[36m[INFO]\033[0m  %s\n' "$*"; }
error() { printf '\033[31m[ERROR]\033[0m %s\n' "$*" >&2; }

# *.java を直接保持できる単純ディレクトリ（app 直下からの相対パス）。
ALLOWED_LEAF_DIRS=" top internal repository log util validation constants "

ROOT=""        # 検証対象プロジェクトのルート（絶対パス）
SRC_REL=""     # ソースルート（ROOT からの相対パス。例: src）
SRC=""         # ソースルート（絶対パス）
declare -a VIOLATIONS=()

add_v() { VIOLATIONS+=("$1"); }

# ROOT からの相対パス表示（区切りは / 正規化）。
relp() { printf '%s' "${1#"$ROOT"/}"; }

lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

# ---------------------------------------------------------------------------
# ソースルート解決（env → application.yaml → 既定 src）
# ---------------------------------------------------------------------------
resolve_src_root() {
  local dir="$1" f v
  if [[ -n "${JAVA_BUILDER_SRC_ROOT:-}" ]]; then
    printf '%s' "$JAVA_BUILDER_SRC_ROOT"
    return
  fi
  for f in "$dir/application.yaml" "$dir/application.yml"; do
    if [[ -f "$f" ]]; then
      # top-level の `src-root: <値>` を抽出（コメント・前後空白・クォートを除去）。
      v="$(grep -E '^[[:space:]]*src-root:' "$f" 2>/dev/null | head -n1 \
            | sed -E 's/^[[:space:]]*src-root:[[:space:]]*//; s/[[:space:]]*#.*$//; s/[[:space:]]+$//; s/^"(.*)"$/\1/; s/^'\''(.*)'\''$/\1/' || true)"
      if [[ -n "$v" ]]; then
        printf '%s' "$v"
        return
      fi
    fi
  done
  printf '%s' "src"
}

# ---------------------------------------------------------------------------
# 許可ディレクトリ判定（leaf 単純ディレクトリ or layer<数値>）
# ---------------------------------------------------------------------------
is_allowed_dir() {
  local d="$1"
  [[ "$ALLOWED_LEAF_DIRS" == *" $d "* ]] && return 0
  [[ "$d" =~ ^layer[0-9]+$ ]] && return 0
  return 1
}

# ---------------------------------------------------------------------------
# D5 命名サフィックス検査
# ---------------------------------------------------------------------------
check_suffix() {
  local prefix="$1" reldir="$2" fn="$3" suffix="$4"
  local base="${fn%.java}"
  if [[ "$base" == *"$suffix" && ${#base} -gt ${#suffix} ]]; then
    return 0
  fi
  add_v "$prefix: $reldir/$fn は命名サフィックス \"$suffix\" で終わる必要があります（例: <ステム>$suffix.java）"
}

# ---------------------------------------------------------------------------
# D4 サービス層の連番（1始まり・歯抜けなし）
# ---------------------------------------------------------------------------
check_layer_sequence() {
  local app="$1" prefix="$2"
  local -a nums=()
  local d name n
  while IFS= read -r d; do
    [[ -z "$d" ]] && continue
    name="$(basename "$d")"
    if [[ "$name" =~ ^layer([0-9]+)$ ]]; then
      nums+=("${BASH_REMATCH[1]}")
    fi
  done < <(find "$app" -mindepth 1 -maxdepth 1 -type d 2>/dev/null)

  [[ ${#nums[@]} -eq 0 ]] && return 0

  # 数値昇順ソート
  local -a sorted=()
  while IFS= read -r n; do sorted+=("$n"); done < <(printf '%s\n' "${nums[@]}" | sort -n)

  local i expected actual
  for i in "${!sorted[@]}"; do
    expected=$((i + 1))
    actual="${sorted[$i]}"
    if [[ "$actual" -ne "$expected" ]]; then
      add_v "$prefix: サービス層の番号が歯抜けです（layer$expected が存在しません）。layer$actual 以降を降格してください。"
      break
    fi
  done
}

# ---------------------------------------------------------------------------
# D2/D3/D5 app/ 配下の配置検査
# ---------------------------------------------------------------------------
check_app() {
  local app="$1" prefix="$2"
  local in_count=0 out_count=0
  local f rel d fn

  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    rel="${f#"$app"/}"
    d="$(dirname "$rel")"
    [[ "$d" == "." ]] && d=""
    fn="$(basename "$f")"

    if [[ -z "$d" ]]; then
      if [[ "$fn" != "Application.java" ]]; then
        add_v "$prefix: app直下に許可されないJavaファイル: $fn（app直下は Application.java のみ許可）"
      fi
    elif [[ "$d" == "dto/in" ]]; then
      in_count=$((in_count + 1))
      check_suffix "$prefix" "$d" "$fn" "InDto"
    elif [[ "$d" == "dto/out" ]]; then
      out_count=$((out_count + 1))
      check_suffix "$prefix" "$d" "$fn" "OutDto"
    elif [[ "$d" == "repository" ]]; then
      check_suffix "$prefix" "$d" "$fn" "Repository"
    elif is_allowed_dir "$d"; then
      :
    else
      add_v "$prefix: 許可されない場所のJavaファイル: $d/$fn"
    fi
  done < <(find "$app" -type f -name '*.java' 2>/dev/null | sort)

  if [[ "$in_count" -ne "$out_count" ]]; then
    add_v "$prefix: dto/in と dto/out のファイル数が一致しません: in=$in_count, out=$out_count"
  fi

  check_layer_sequence "$app" "$prefix"
}

# ---------------------------------------------------------------------------
# ルール17（構造部分）: 統合テストディレクトリの存在と .java の有無
# ---------------------------------------------------------------------------
check_integration() {
  local name="$1"
  local idir="$SRC/test/java/com/$name/integration"
  local relid="$SRC_REL/test/java/com/$name/integration"

  if [[ ! -d "$idir" ]]; then
    add_v "統合テストディレクトリが存在しません: $relid"
    return
  fi
  if ! find "$idir" -type f -name '*.java' 2>/dev/null | grep -q .; then
    add_v "統合テストディレクトリに .java ファイルがありません: $relid"
  fi
}

# ---------------------------------------------------------------------------
# ファイル種別の配置検査（main/test 共通）
# ---------------------------------------------------------------------------
check_file_types() {
  local stage jdir rdir f lf
  for stage in main test; do
    jdir="$SRC/$stage/java"
    if [[ -d "$jdir" ]]; then
      while IFS= read -r f; do
        [[ -z "$f" ]] && continue
        lf="$(lower "$(basename "$f")")"
        case "$lf" in
          *.java) ;;
          *) add_v "$(relp "$f") は .java のみ配置できます: $(basename "$f")" ;;
        esac
      done < <(find "$jdir" -type f 2>/dev/null | sort)
    fi
    rdir="$SRC/$stage/resources"
    if [[ -d "$rdir" ]]; then
      while IFS= read -r f; do
        [[ -z "$f" ]] && continue
        lf="$(lower "$(basename "$f")")"
        case "$lf" in
          *.yaml|*.yml) ;;
          *) add_v "$(relp "$f") は .yaml／.yml のみ配置できます: $(basename "$f")" ;;
        esac
      done < <(find "$rdir" -type f 2>/dev/null | sort)
    fi
  done
}

# ---------------------------------------------------------------------------
# メイン
# ---------------------------------------------------------------------------
main() {
  if [[ $# -lt 1 ]]; then
    error "使い方: $0 <検証対象プロジェクトのルート>"
    exit 1
  fi
  ROOT="$(cd "$1" && pwd)"

  SRC_REL="$(resolve_src_root "$ROOT")"
  SRC="$ROOT/$SRC_REL"
  info "ソースルート: $SRC_REL（ROOT=$ROOT）"

  # 1) ファイル種別の配置（com の有無に依らず常に検査）
  check_file_types

  # 2) ルート構成 D1: <src>/main/java/com の存在
  local com="$SRC/main/java/com"
  if [[ ! -d "$com" ]]; then
    add_v "必須ディレクトリが存在しません: $SRC_REL/main/java/com"
    report
    return
  fi

  # 3) プロジェクトごとに app 配下と統合テスト構成を検査
  local valid_projects=0
  local proj name app
  while IFS= read -r proj; do
    [[ -z "$proj" ]] && continue
    name="$(basename "$proj")"
    if ! [[ "$name" =~ ^[0-9a-zA-Z_-]+$ ]]; then
      add_v "プロジェクト名ディレクトリが命名規約 ^[0-9a-zA-Z-_]+\$ に違反: com/$name"
      continue
    fi
    app="$proj/app"
    if [[ ! -d "$app" ]]; then
      add_v "app ディレクトリが存在しません: com/$name/app"
      continue
    fi
    valid_projects=$((valid_projects + 1))
    check_app "$app" "com/$name/app"
    check_integration "$name"
  done < <(find "$com" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort)

  if [[ "$valid_projects" -eq 0 && ${#VIOLATIONS[@]} -eq 0 ]]; then
    add_v "com 配下にプロジェクト名ディレクトリが存在しません（期待構成: com/<projectName>/app）"
  fi

  report
}

# 違反を出力し、1件以上あれば exit 1。
report() {
  if [[ ${#VIOLATIONS[@]} -gt 0 ]]; then
    error "ディレクトリ構成エラー ($ROOT):"
    local v
    for v in "${VIOLATIONS[@]}"; do
      error "  - $v"
    done
    exit 1
  fi
  info "ディレクトリ構成チェック: OK ($ROOT)"
}

main "$@"
