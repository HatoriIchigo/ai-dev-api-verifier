# IF 仕様書（docs/backend.openapi.yaml）の生成要件

OpenAPI 形式の IF 仕様書（API 契約）。`ifspec-verifier`（redocly 標準 lint ＋ 独自規約）を通す必要が
ある。生成時に次を**すべて**満たすこと。

## 1. 先頭フロントマター（独自チェック・必須）

ファイル先頭のコメントブロックに以下 5 項目を**すべて**記載する（値が空でも欠落扱い）。

```yaml
#
# filename: backend.openapi.yaml
# description: backend の IF 仕様書
# created_by: <作成者>
# created_at: <YYYY-MM-DD>
# status: <draft / approved など>
#

openapi: 3.0.3
...
```

必須項目: `filename` / `description` / `created_by` / `created_at` / `status`。

## 2. x-internal（独自チェック・必須）

各 path 配下の各 HTTP メソッドに `x-internal` を付ける。外部接続の有無を**宣言**する要であり、
設計 JSON のゾーン（`top/` か `internal/`）と一致させる。

- 存在が必須。値は `true`（外部接続あり）または `false`（外部接続なし）のいずれか。
- `x-internal: true` のときは**行末コメントが必須**で、コメントは `外部接続:` で始め、接続先を書く。

## 3. redocly 標準 lint（必須）

`recommended` ベースライン＋以下が `error`。生成時に漏らすと検証に落ちるので必ず満たす。

- 全 operation に **`operationId`**（一意）。
- 全 operation に **`summary`**。
- 全 operation に **4xx レスポンスを 1 つ以上**（例: `'400'` や `'404'`）。
- スカラープロパティ（`string`/`number`/`integer`/`boolean`）に **`example`** を付ける。
- **`security` を定義する**（`components.securitySchemes` を用意し、ルート直下の `security:` か各 operation に適用）。
  認証不要のエンドポイントは当該 operation に `security: []` を書いて明示的に外す。
- `servers` の URL に **`example.com` / `localhost` を使わない**（実ホストかプレースホルダのドメイン）。
- `info.license` は不要（社内 IF 仕様のため無効化済み。あえて付けなくてよい）。

## 生成例（最小・検証を通る形）

```yaml
#
# filename: backend.openapi.yaml
# description: backend の IF 仕様書
# created_by: vxdora
# created_at: 2026-06-18
# status: draft
#

openapi: 3.0.3
info:
  title: backend API
  version: 1.0.0
servers:
  - url: https://api.example-corp.internal
security:
  - bearerAuth: []
components:
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
paths:
  /accounts/login:
    post:
      operationId: loginAccount
      summary: アカウントのログイン
      description: アカウントのログイン処理。
      x-internal: true # 外部接続: DB/Cognito
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              properties:
                userId:
                  type: string
                  example: "user-001"
      responses:
        '200':
          description: 成功
          content:
            application/json:
              schema:
                type: object
                properties:
                  token:
                    type: string
                    example: "eyJhbGc..."
        '400':
          description: リクエスト不正
```

## 検証

```bash
cd ifspec-verifier && ./verify.sh ../docs/backend.openapi.yaml
```

`[ERROR]` が出ず終了コード 0 になることを確認する。違反があれば該当箇所を直す。
