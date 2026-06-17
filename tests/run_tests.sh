#!/usr/bin/env bash
# verify.sh の単体テストランナー（並列実行対応）。
# tests/test_* 配下の openapi.yaml を verify.sh で検証し、
# 期待する結果(expected)と一致するかを確認する。
#   expected の値:
#     pass        … 検証成功 (exit 0)
#     redocly     … redocly lint のみ失敗（operationId 必須もここに含む）
#     operations  … x-internal チェックのみ失敗
#     frontmatter … フロントマターチェックのみ失敗
# （npm / redocly / yq の有無チェックはテスト対象外）
#
# 並列数は第1引数または環境変数 MAX_JOBS で指定（デフォルト 4）。
#   例: ./run_tests.sh 8   /   MAX_JOBS=2 ./run_tests.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY="$SCRIPT_DIR/../verify.sh"
MAX_JOBS="${1:-${MAX_JOBS:-4}}"

RESULT_DIR="$(mktemp -d)"
trap 'rm -rf "$RESULT_DIR"' EXIT

# 1 テストを実行し、結果を "$RESULT_DIR/<name>" に "ok|message" 形式で書き出す
run_one() {
  local dir="$1"
  local name spec expected output actual rstat ostat fstat ok detail
  name="$(basename "$dir")"
  spec="$dir/openapi.yaml"

  if [ ! -f "$spec" ]; then
    printf 'skip|SKIP %s: openapi.yaml がありません\n' "$name" > "$RESULT_DIR/$name"
    return
  fi
  expected="$(cat "$dir/expected")"

  output="$("$VERIFY" "$spec" 2>&1)"
  actual=$?

  rstat="$(printf '%s\n' "$output" | sed -n 's/.*redocly=\([0-9]*\).*/\1/p' | tail -1)"
  ostat="$(printf '%s\n' "$output" | sed -n 's/.*operations=\([0-9]*\).*/\1/p' | tail -1)"
  fstat="$(printf '%s\n' "$output" | sed -n 's/.*frontmatter=\([0-9]*\).*/\1/p' | tail -1)"

  ok=false
  case "$expected" in
    pass)
      [ "$actual" -eq 0 ] && ok=true ;;
    redocly)
      { [ "$actual" -ne 0 ] && [ "${rstat:-0}" -ne 0 ] && [ "${ostat:-0}" -eq 0 ] && [ "${fstat:-0}" -eq 0 ]; } && ok=true ;;
    operations)
      { [ "$actual" -ne 0 ] && [ "${ostat:-0}" -ne 0 ] && [ "${rstat:-0}" -eq 0 ] && [ "${fstat:-0}" -eq 0 ]; } && ok=true ;;
    frontmatter)
      { [ "$actual" -ne 0 ] && [ "${fstat:-0}" -ne 0 ] && [ "${rstat:-0}" -eq 0 ] && [ "${ostat:-0}" -eq 0 ]; } && ok=true ;;
    *)
      printf 'fail|FAIL %s: 未知の expected 値: %s\n' "$name" "$expected" > "$RESULT_DIR/$name"
      return ;;
  esac

  detail="actual_exit=$actual redocly=${rstat:-0} operations=${ostat:-0} frontmatter=${fstat:-0}"
  if [ "$ok" = true ]; then
    printf 'pass|PASS %s (expected=%s %s)\n' "$name" "$expected" "$detail" > "$RESULT_DIR/$name"
  else
    printf 'fail|FAIL %s (expected=%s %s)\n' "$name" "$expected" "$detail" > "$RESULT_DIR/$name"
  fi
}

echo "並列数: $MAX_JOBS"

# 並列実行（同時実行数を MAX_JOBS に制限）
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

# 結果をテスト名順に集計・表示
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
