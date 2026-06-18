#!/usr/bin/env bash
set -euo pipefail

# 引数チェック: 検証するファイルを受け取る
if [ "$#" -lt 1 ]; then
  echo "Usage: $0 <api-spec-file>" >&2
  exit 1
fi

SPEC_FILE="$1"

if [ ! -f "$SPEC_FILE" ]; then
  echo "Error: ファイルが見つかりません: $SPEC_FILE" >&2
  exit 1
fi

# npm が入っていなければエラー終了
if ! command -v npm >/dev/null 2>&1; then
  echo "Error: npm がインストールされていません。Node.js / npm をインストールしてください。" >&2
  exit 1
fi

# redocly が入っていなければインストール
if ! command -v redocly >/dev/null 2>&1; then
  echo "redocly が見つかりません。インストールします..."
  npm install -g @redocly/cli
fi

echo "redocly: $(redocly --version)"

# yq が入っていなければインストール
if ! command -v yq >/dev/null 2>&1; then
  echo "yq が見つかりません。インストールします..."
  YQ_VERSION="v4.44.6"
  case "$(uname -m)" in
    x86_64 | amd64) YQ_ARCH="amd64" ;;
    aarch64 | arm64) YQ_ARCH="arm64" ;;
    *)
      echo "Error: 未対応のアーキテクチャです: $(uname -m)" >&2
      exit 1
      ;;
  esac
  YQ_TMP="$(mktemp)"
  curl -fsSL "https://github.com/mikefarah/yq/releases/download/${YQ_VERSION}/yq_linux_${YQ_ARCH}" -o "$YQ_TMP"
  chmod +x "$YQ_TMP"
  mv "$YQ_TMP" /usr/local/bin/yq
fi

echo "yq: $(yq --version)"

# 各 path 配下の HTTP メソッドの x-internal(true/false) を確認する。
# （operationId の必須チェックは redocly の operation-operationId ルールに一本化）
check_operations() {
  local file="$1"
  local http_methods="get post put delete patch options head trace"
  local errors=0
  local paths
  paths="$(yq e '.paths // {} | keys | .[]' "$file")"

  if [ -z "$paths" ]; then
    echo "  paths が見つかりません" >&2
    return 1
  fi

  while IFS= read -r path; do
    [ -z "$path" ] && continue
    for method in $http_methods; do
      # 該当メソッドが定義されているか
      local present
      present="$(yq e ".paths[\"$path\"] | has(\"$method\")" "$file")"
      [ "$present" = "true" ] || continue

      # x-internal の有無と値(true/false)
      local has_xint
      has_xint="$(yq e ".paths[\"$path\"][\"$method\"] | has(\"x-internal\")" "$file")"
      if [ "$has_xint" = "true" ]; then
        local xint
        xint="$(yq e ".paths[\"$path\"][\"$method\"][\"x-internal\"]" "$file")"
        if [ "$xint" = "true" ]; then
          # x-internal: true の場合は行末コメントが必須で、
          # コメントは「外部接続:」で始まる必要がある。
          local comment trimmed
          comment="$(yq e ".paths[\"$path\"][\"$method\"][\"x-internal\"] | line_comment" "$file")"
          # 先頭の空白を除去（yq は「#」直後の余分な空白を残すため）
          trimmed="${comment#"${comment%%[![:space:]]*}"}"
          if [ -z "$trimmed" ]; then
            echo "  [NG] ${method} ${path}: x-internal:true には行末コメントが必要です (例: '# 外部接続: DB/Cognito')" >&2
            errors=$((errors + 1))
          elif [[ "$trimmed" != 外部接続:* ]]; then
            echo "  [NG] ${method} ${path}: コメントは '外部接続:' で始まる必要があります (現在: '${trimmed}')" >&2
            errors=$((errors + 1))
          else
            echo "  [OK] ${method} ${path}: x-internal=true (${trimmed})"
          fi
        elif [ "$xint" = "false" ]; then
          echo "  [OK] ${method} ${path}: x-internal=false"
        else
          echo "  [NG] ${method} ${path}: x-internal は true/false である必要があります (現在: ${xint})" >&2
          errors=$((errors + 1))
        fi
      else
        echo "  [NG] ${method} ${path}: x-internal がありません" >&2
        errors=$((errors + 1))
      fi
    done
  done <<< "$paths"

  [ "$errors" -eq 0 ]
}

# ファイル先頭のコメント・フロントマターを検証する
# 例:
#   #
#   # filename: openapi.json
#   # description: backendのIF仕様書
#   # created_by: vxdora
#   # created_at: 2026-06-16
#   # status: approved
#   #
check_frontmatter() {
  local file="$1"
  local errors=0
  local header field value

  # 先頭の連続するコメント行(# で始まる行)をフロントマターとして取り出す
  header="$(awk '!/^[[:space:]]*#/{exit} {print}' "$file")"

  if [ -z "$header" ]; then
    echo "  [NG] フロントマター(先頭コメント)がありません" >&2
    return 1
  fi

  # 指定キーの値を取得するヘルパー（末尾の空白は除去）
  get_field() {
    printf '%s\n' "$header" \
      | sed -n "s/^[[:space:]]*#[[:space:]]*$1:[[:space:]]*\(.*\)$/\1/p" \
      | head -1 \
      | sed 's/[[:space:]]*$//'
  }

  # 必須キーの存在チェック
  for field in filename description created_by created_at status; do
    value="$(get_field "$field")"
    if [ -z "$value" ]; then
      echo "  [NG] フロントマター: ${field} がありません" >&2
      errors=$((errors + 1))
    else
      echo "  [OK] フロントマター: ${field}=${value}"
    fi
  done

  [ "$errors" -eq 0 ]
}

# 引数で渡されたファイルを redocly で検証
echo "検証中: $SPEC_FILE"
redocly_status=0
redocly lint "$SPEC_FILE" || redocly_status=$?

# x-internal の確認
echo "x-internal を確認中..."
check_status=0
check_operations "$SPEC_FILE" || check_status=$?

# フロントマターの確認
echo "フロントマターを確認中..."
fm_status=0
check_frontmatter "$SPEC_FILE" || fm_status=$?

if [ "$redocly_status" -ne 0 ] || [ "$check_status" -ne 0 ] || [ "$fm_status" -ne 0 ]; then
  echo "検証に失敗しました (redocly=$redocly_status, operations=$check_status, frontmatter=$fm_status)" >&2
  exit 1
fi
echo "検証に成功しました: $SPEC_FILE"
