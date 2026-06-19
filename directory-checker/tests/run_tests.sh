#!/usr/bin/env bash
# directory-checker/verify.sh の単体テストランナー（並列実行対応）。
# tests/test_*/ 配下の project ツリーを verify.sh で検証し、期待結果(expected)と一致するか確認する。
#
#   test_*/ の構成:
#     project/   … 検証対象ディレクトリの素材（--dir に渡すツリー。必須）
#     expected   … pass（exit 0）または fail（exit 非0）（必須）
#     type       … --type に渡す値（任意。既定 backend）
#     env        … verify.sh 実行時に設定する環境変数（任意。1行=KEY=VALUE）
#     mkdirs     … 実行前に作成する空ディレクトリ（任意。1行=project からの相対パス）。
#                  git は空ディレクトリを追跡できないため、空ディレクトリに依存するケースで用いる。
#     match      … 出力に含むべき部分文字列（任意。1行=1つ。「1テスト=1失敗要因」固定用）
#
# 各テストは project/ を一時ディレクトリへコピーしてから（必要なら mkdirs を適用して）検証するため、
# リポジトリ内のフィクスチャは変更されない。
#
# 並列数は第1引数または環境変数 MAX_JOBS で指定（デフォルト 4）。
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY="$SCRIPT_DIR/../verify.sh"
MAX_JOBS="${1:-${MAX_JOBS:-4}}"

RESULT_DIR="$(mktemp -d)"
trap 'rm -rf "$RESULT_DIR"' EXIT

run_one() {
  local dir="$1"
  local name project expected type output actual stage ok missing work
  name="$(basename "$dir")"
  project="$dir/project"

  if [ ! -d "$project" ]; then
    printf 'fail|FAIL %s: project/ ディレクトリがありません\n' "$name" > "$RESULT_DIR/$name"
    return
  fi
  if [ ! -f "$dir/expected" ]; then
    printf 'fail|FAIL %s: expected がありません\n' "$name" > "$RESULT_DIR/$name"
    return
  fi
  expected="$(cat "$dir/expected")"
  type="backend"
  [ -f "$dir/type" ] && type="$(cat "$dir/type")"

  # project/ を一時ディレクトリへコピー（フィクスチャを汚さない）。
  work="$(mktemp -d)"
  cp -r "$project/." "$work/"
  # mkdirs（空ディレクトリ）を適用。
  if [ -f "$dir/mkdirs" ]; then
    while IFS= read -r d || [ -n "$d" ]; do
      [ -z "$d" ] && continue
      mkdir -p "$work/$d"
    done < "$dir/mkdirs"
  fi

  # env ファイルがあれば環境変数として渡す（サブシェルに閉じる）。
  if [ -f "$dir/env" ]; then
    output="$(set -a; . "$dir/env"; set +a; "$VERIFY" --dir "$work" --type "$type" 2>&1)"
  else
    output="$("$VERIFY" --dir "$work" --type "$type" 2>&1)"
  fi
  actual=$?
  rm -rf "$work"

  if [ "$actual" -eq 0 ]; then stage="pass"; else stage="fail"; fi

  ok=true
  case "$expected" in
    pass|fail) [ "$stage" = "$expected" ] || ok=false ;;
    *)
      printf 'fail|FAIL %s: 未知の expected 値: %s\n' "$name" "$expected" > "$RESULT_DIR/$name"
      return ;;
  esac

  missing=""
  if [ -f "$dir/match" ]; then
    while IFS= read -r needle || [ -n "$needle" ]; do
      [ -z "$needle" ] && continue
      printf '%s\n' "$output" | grep -qF -- "$needle" || missing="$missing [$needle]"
    done < "$dir/match"
    [ -n "$missing" ] && ok=false
  fi

  local detail="actual_exit=$actual stage=$stage type=$type"
  [ -n "$missing" ] && detail="$detail match未検出:$missing"
  if [ "$ok" = true ]; then
    printf 'pass|PASS %s (expected=%s %s)\n' "$name" "$expected" "$detail" > "$RESULT_DIR/$name"
  else
    printf 'fail|FAIL %s (expected=%s %s)\n' "$name" "$expected" "$detail" > "$RESULT_DIR/$name"
  fi
}

echo "並列数: $MAX_JOBS"

running=0
for dir in "$SCRIPT_DIR"/test_*/; do
  [ -d "$dir" ] || continue
  run_one "$dir" &
  running=$((running + 1))
  if [ "$running" -ge "$MAX_JOBS" ]; then
    wait -n
    running=$((running - 1))
  fi
done
wait

pass=0
fail=0
for f in "$RESULT_DIR"/*; do
  [ -f "$f" ] || continue
  line="$(cat "$f")"
  echo "${line#*|}"
  case "${line%%|*}" in
    pass) pass=$((pass + 1)) ;;
    fail) fail=$((fail + 1)) ;;
  esac
done

echo "-----------------------------------"
echo "Result: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
