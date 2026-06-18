# ifspec-verifier

OpenAPI 仕様書（`openapi.yaml` / `openapi.json`）を検証するシェルスクリプトです。
[redocly](https://redocly.com/docs/cli/) による標準 lint に加えて、プロジェクト独自のルール
（`x-internal` の運用規約・先頭フロントマター）を検証します。

スクリプト一式は `ifspec-verifier/` 配下にあります。

## 必要環境

| ツール | 用途 | 不在時の挙動 |
|--------|------|------------|
| `npm` (Node.js) | redocly のインストール | **エラー終了**（手動インストールが必要） |
| `redocly` (`@redocly/cli`) | OpenAPI 標準 lint | 自動インストール（`npm install -g`） |
| `yq` ([mikefarah/yq](https://github.com/mikefarah/yq)) | YAML/コメントの解析 | 自動インストール（GitHub バイナリ取得） |
| `curl` | yq バイナリのダウンロード | 前提（標準環境にあること） |

## 使い方

`redocly lint` はカレントディレクトリの `redocly.yaml` を読むため、`ifspec-verifier/` に移動して実行します。

```bash
cd ifspec-verifier
./verify.sh <api-spec-file>

# 例
./verify.sh openapi.yaml
```

検証は次の3段で行われ、**いずれか1つでも失敗すると終了コード 1** で終了します。
成功時は終了コード 0 です（CI のゲートに利用できます）。

```text
検証中: openapi.yaml
redocly: 2.x.x
yq: ...
x-internal を確認中...
フロントマターを確認中...
検証に成功しました: openapi.yaml
```

失敗時は内訳が表示されます。

```text
検証に失敗しました (redocly=1, operations=0, frontmatter=0)
```

## 検証内容

### 1. redocly lint

`ifspec-verifier/redocly.yaml` の設定に従って OpenAPI を lint します。
`recommended` をベースラインに、商用品質向けにルールを調整しています。
詳細は `redocly.yaml` 内のコメントを参照してください。主な追加・変更:

- `operation-operationId: error` — 全 operation に `operationId` 必須
- `operation-summary: error`（recommended 既定）— summary 必須
- `operation-4xx-response: error` — 全 operation に 4xx レスポンス必須
- `no-server-example.com: error` — server URL に example.com / localhost を禁止
- `scalar-property-missing-example: error` — スカラープロパティに example 必須
- `info-license: off` — 社内 IF 仕様のためライセンス必須を無効化

### 2. x-internal の検証（自前チェック）

各 path 配下の HTTP メソッドについて、`x-internal` を検証します。

- `x-internal` の **存在** が必須
- 値は **`true` または `false`** のいずれか
- `x-internal: true` の場合、**行末コメントが必須**で、コメントは **`外部接続:`** で始まること

```yaml
paths:
  /user:
    post:
      operationId: createUser
      x-internal: true # 外部接続: DB/Cognito   # ← true のときコメント必須
```

> `operationId` の必須チェックは redocly（`operation-operationId`）に一本化しています。

### 3. フロントマターの検証（自前チェック）

ファイル先頭のコメントブロックに、次の項目が **すべて存在**することを検証します
（値が空の場合も欠落として扱います）。

```yaml
#
# filename: openapi.yaml
# description: backendのIF仕様書
# created_by: vxdora
# created_at: 2026-06-16
# status: approved
#

openapi: 3.0.3
...
```

必須項目: `filename` / `description` / `created_by` / `created_at` / `status`

## テスト

```bash
cd ifspec-verifier
# 全テストを並列実行（デフォルト 4 並列）
./tests/run_tests.sh

# 並列数を変更
./tests/run_tests.sh 8
MAX_JOBS=2 ./tests/run_tests.sh
```

`ifspec-verifier/tests/test_NN/` に検証対象の `openapi.yaml` と期待結果 `expected` を置きます。
`expected` の値:

| 値 | 意味 |
|----|------|
| `pass` | 検証成功（exit 0） |
| `redocly` | redocly lint のみ失敗 |
| `operations` | x-internal チェックのみ失敗 |
| `frontmatter` | フロントマターチェックのみ失敗 |

ランナーは終了コードに加え、失敗内訳 `(redocly=X, operations=Y, frontmatter=Z)` を解析し、
**狙った検証だけが失敗したか**まで照合します。

### テストの追加方法

```bash
cd ifspec-verifier
mkdir tests/test_NN
# 検証対象の spec を配置
$EDITOR tests/test_NN/openapi.yaml
# 期待結果を記述（pass / redocly / operations / frontmatter のいずれか）
echo pass > tests/test_NN/expected
```

## 検証ロジックの設計方針

- **redocly で表現できる検証は redocly に寄せる**（自前実装と二重化しない）。
  例: `operationId` 必須・`example` 必須は redocly のルールで担保する。
- **redocly で表現できない独自規約のみ自前チェック（yq/bash）で実装する**。
  例: `x-internal` の値・行末コメント規約、先頭フロントマター。
- 各テストは **1つの失敗要因だけ**を発生させるよう設計する（`redocly` / `operations` / `frontmatter` を分離）。
  ランナーはどの検証で失敗したかまで照合する。この分離を壊さないこと。

## 重要な設計判断（変更時は要確認）

- `operationId` の必須チェックは **redocly に一本化**済み（自前チェックには戻さない）。
- `example` 必須は redocly（`scalar-property-missing-example`）に任せる方針。
- `x-internal` の値・コメント規約、フロントマターは redocly で表現できないため **自前チェックで実装**している。
- 5xx レスポンスを検証する redocly ルールは存在しない（要件化する場合は自前実装が必要）。

## 主要コマンド

```bash
cd ifspec-verifier

# 検証の実行
./verify.sh <api-spec-file>

# 全テストを並列実行（デフォルト 4 並列）
./tests/run_tests.sh
./tests/run_tests.sh 8          # 並列数を指定
MAX_JOBS=2 ./tests/run_tests.sh

# 構文チェック
bash -n verify.sh

# 個別 spec の lint 内訳を確認
redocly lint tests/test_01/openapi.yaml
```

## ディレクトリ構成

```text
ifspec-verifier/
├── verify.sh          # 検証スクリプト本体
├── redocly.yaml       # redocly lint のルール設定（変更理由はコメントで管理）
└── tests/
    ├── run_tests.sh   # 並列テストランナー
    └── test_NN/
        ├── openapi.yaml
        └── expected
```
