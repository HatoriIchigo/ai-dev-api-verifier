# アーキテクチャ設計 JSON（docs/backend.architecture.json）の生成要件

レイヤードアーキテクチャの「元となる」設計 JSON。各エンドポイント・レイヤー・repository・DTO の
依存関係を宣言する。`layered-checker`（`layered-checker/verify.sh`）で検証する。
各ルール末尾の `[...]` は構成ルールの分類名・番号（参考表示）。

## 全体構造

```jsonc
{
  "projectName": "sample",                 // ^[0-9A-Za-z_-]+$
  "openapi": "docs/backend.openapi.yaml",  // 対応する IF 仕様書のパス

  "top":        [ /* エンドポイントのエントリ（top/ と internal/ の両方を含む） */ ],
  "layer":      { "layer1": [ /* ... */ ], "layer2": [ /* ... */ ] },
  "internal":   { /* backend 完結エンドポイントのコンポーネント定義 */ },
  "repository": [ /* 外部連携 */ ],
  "dto":        { "in": [ /* ... */ ], "out": [ /* ... */ ] }
}
```

- **必須トップレベルキー**: `top` / `layer` / `repository` / `dto`(欠けるとエラー)。
- `projectName` / `openapi` / `internal` は任意だが、ある場合は下記ルールの対象。

## import 参照記法

依存は `imports` 配列に `"<zone>/<Name>"` 形式の文字列で書く。解決可能な zone:
`layer<N>/` ・ `repository/` ・ `internal/` ・ `dto/in/` ・ `dto/out/` ・ `constants/` ・
`validation/` ・ `util/` ・ `log/`。

- **すべての `imports` 宛先は実在すること**（dangling 参照は禁止）。

## top（エンドポイント）

各要素に `path` / `method` / `description` / `imports` が**必須**。`operationId` は IF 仕様書の
operation と 1:1 対応させ、`entry` は `top/Xxx`（外部接続あり）または `internal/Xxx`（backend 完結）。

- IF 仕様書で `x-internal: true`（外部接続あり）のエンドポイント → `entry` は `top/`。
- IF 仕様書で `x-internal: false`／省略（外部接続なし）のエンドポイント → `entry` は `internal/`。
- `imports` で参照できるレイヤーは **最大番号のレイヤー（`layer<最大>`）のみ**（[code-rule 8]）。
- `top` のエントリは `external` を持てない（[layer-rule 15]）。

## layer（サービス層）

`layer` はオブジェクトで、キーは `layer<数値>` 形式。各レイヤーは要素配列で、各要素に
`name` / `description` / `imports` を持つ（`imports` 必須）。

- レイヤー番号は **1 始まりの連番**であること（歯抜け禁止）（[directory-structure]）。
- `layer1` の各要素は **`repository/` を import 必須**（[code-rule 3]）。
- `layer<N>`（N≥2）は **下位レイヤー（`layer1`〜`layer<N-1>`）のいずれかを import 必須**（[code-rule 7]）。
- レイヤー差 ≥2 の import は禁止（`layerN` は `layer(N-1)` までしか参照不可）（[layer-rule 14]）。
- `layer` の要素は `external` を持てない（[layer-rule 15]）。

## internal（backend 完結コンポーネント）

外部接続を伴わないエンドポイントが使うコンポーネント定義。各要素に `name` / `description` /
（必要なら）`imports`。

- **推移的な import 閉包に `repository/` が現れてはならない**（backend 完結であることの保証）（[code-rule 10]）。
- `internal` の要素は `external` を持てない（[layer-rule 15]）。

## repository（外部連携）

各要素に `name` / `description` / `imports`（必須）と、任意で `external`。

- `imports` は **`dto/in/<name>` と `dto/out/<name>` をそれぞれちょうど 1 件**含むこと（[code-rule 4]）。
- `repository/Foo` には対応する `dto/in/Foo`・`dto/out/Foo` が存在すること（[code-rule 4]）。
- **`external` を持てるのは `repository` のみ**（[layer-rule 15]）。形式は次の 3 通り:
  - 文字列（cognito 等その他の連携）
  - `{ "db":  { "schema": "<スキーマ>" } }`
  - `{ "api": { "url": "<URL>", "method": "<METHOD>" } }`

## dto

`dto.in` / `dto.out` は DTO 名（文字列）の配列。

- **`dto.in` と `dto.out` の件数が一致**すること（[directory-structure]）。

## 生成例

```json
{
  "projectName": "sample",
  "openapi": "docs/backend.openapi.yaml",
  "top": [
    {
      "operationId": "loginAccount",
      "entry": "top/LoginAccount",
      "path": "/accounts/login",
      "method": "POST",
      "description": "ログイン処理（外部接続あり）",
      "imports": ["layer2/AccountService"]
    },
    {
      "operationId": "checkHealth",
      "entry": "internal/CheckHealth",
      "path": "/health",
      "method": "GET",
      "description": "ヘルスチェック（backend 完結・repository 到達禁止）",
      "imports": ["internal/HealthChecker"]
    }
  ],
  "layer": {
    "layer1": [
      { "name": "AccountStore", "description": "データアクセス層", "imports": ["repository/Account"] }
    ],
    "layer2": [
      { "name": "AccountService", "description": "業務ロジック層", "imports": ["layer1/AccountStore"] }
    ]
  },
  "internal": {
    "HealthChecker": [
      { "name": "HealthChecker", "description": "稼働状態の判定（repository 不到達）" }
    ]
  },
  "repository": [
    {
      "name": "Account",
      "description": "アカウント DB 連携",
      "external": { "db": { "schema": "accounts.login" } },
      "imports": ["dto/in/Account", "dto/out/Account"]
    }
  ],
  "dto": { "in": ["Account"], "out": ["Account"] }
}
```

## 検証

```bash
cd layered-checker && ./verify.sh ../docs/backend.architecture.json
```

`[ERROR]` が出ず終了コード 0 になることを確認する。違反があれば該当ルールに従って修正する。
