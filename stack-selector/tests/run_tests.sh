#!/usr/bin/env bash
# verify.sh の単体テストランナー（並列実行対応）。
# tests/test_* 配下の input.json を verify.sh で検証し、期待結果(expected)と一致するか確認する。
#
#   expected の値:
#     pass … 検証成功 (exit 0)
#     fail … 検証失敗 (exit 非0)
#
#   任意ファイル match:
#     test_* ディレクトリに match を置くと、その内容（1行=1部分文字列）がすべて
#     verify.sh の出力に含まれることも追加で確認する。「1テスト=1失敗要因」を固定するために用いる。
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
  local name input expected output actual stage ok missing
  name="$(basename "$dir")"
  input="$dir/input.json"

  if [ ! -f "$input" ]; then
    printf 'skip|SKIP %s: input.json がありません\n' "$name" > "$RESULT_DIR/$name"
    return
  fi
  if [ ! -f "$dir/expected" ]; then
    printf 'fail|FAIL %s: expected がありません\n' "$name" > "$RESULT_DIR/$name"
    return
  fi
  expected="$(cat "$dir/expected")"

  output="$("$VERIFY" "$input" 2>&1)"
  actual=$?

  if [ "$actual" -eq 0 ]; then stage="pass"; else stage="fail"; fi

  ok=true
  case "$expected" in
    pass|fail) [ "$stage" = "$expected" ] || ok=false ;;
    *)
      printf 'fail|FAIL %s: 未知の expected 値: %s\n' "$name" "$expected" > "$RESULT_DIR/$name"
      return ;;
  esac

  # match（任意）: 各行の部分文字列がすべて出力に含まれること
  missing=""
  if [ -f "$dir/match" ]; then
    while IFS= read -r needle || [ -n "$needle" ]; do
      [ -z "$needle" ] && continue
      printf '%s\n' "$output" | grep -qF -- "$needle" || missing="$missing [$needle]"
    done < "$dir/match"
    [ -n "$missing" ] && ok=false
  fi

  local detail="actual_exit=$actual stage=$stage"
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
