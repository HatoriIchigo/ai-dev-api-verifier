# layered-checker

レイヤードアーキテクチャの「元となる」JSON 設計ファイルを検証するシェルスクリプトです。
Java の実装（`java-builder`）を生成・検査する前段として、設計 JSON が構成ルール・レイヤールールを
満たしているかをチェックします。

スクリプトは `layered-checker/verify.sh` です。

## 技術スタック

- bash（冒頭で `set -euo pipefail`）
- `jq` — JSON の構文解析・検証ロジックの中核（未インストール時は OS のパッケージマネージャ経由で自動インストールを試みる）

## 使い方

```bash
cd layered-checker
./verify.sh <input.json>

# 例（同梱サンプル）
./verify.sh sample/sample.json
```

検証は次の3段で順に行われ、**いずれかで違反を検出すると終了コード 1**（使い方エラー・ファイル不在も 1）で終了します。
成功時は終了コード 0 です。違反内容は `[ERROR]` 行として標準エラー出力に出ます。

## 検証内容

### 1. JSON 構文（`verify_json`）

- ファイルの存在を確認する。
- `jq empty` で JSON として妥当かを確認する。

### 2. レイヤー構成（`verify_structure`）

JSON の構造（必須キー・形式）を検証します。

- 必須トップレベルキー: `top` / `layer` / `repository` / `dto`
- `layer` のキーは `layer<数値>` 形式であること
- `top` の各エンドポイントに `path` / `method` / `description` / `imports` が必須
- `dto` 以外の各コンポーネント定義（`layer.*`・`repository`）に `imports` が必須
- `repository.external` の形式:
  - 文字列（cognito 等のその他連携）
  - `{ "db":  { "schema": ... } }`
  - `{ "api": { "url": ..., "method": ... } }`

### 3. レイヤールール（`verify_rules`）

ルール本体は [`docs/code-rule.md`](code-rule.md) に一元管理しています。`layered-checker` はそのうち
**構造的に判定できる**ルール（`code-rule.md` で「構造判定可」と印のあるもの）のみを実装します。
文字列ハードコード／if-for 禁止／サイズ制限など AST レベルのルールは Java ソースが必要なため
`layered-checker` の対象外で、[`java-builder`](java-builder.md) が担います。

各ルールの末尾 `[...]` は [`docs/code-rule.md`](code-rule.md) の該当番号です。

- `projectName` 形式（`^[0-9A-Za-z_-]+$`） — [directory-structure]
- `layer` 連番（1始まり・歯抜けなし） — [directory-structure]
- `dto.in` と `dto.out` の件数一致 — [directory-structure]
- `repository/Foo` ↔ `dto/in/Foo`・`dto/out/Foo` の対応（各ちょうど1件） — [code-rule 4]
- `import` 宛先の実在（dangling 参照検出） — [整合性]
- `layer1` は `repository` を import 必須 — [code-rule 3]
- `layer<N>`（N≥2）は下位レイヤーを import 必須 — [code-rule 7]
- レイヤー差 ≥2 の import 禁止 — [layer-rule 14]
- `top` は最大レイヤーのみ import 可 — [code-rule 8]
- `external` は `repository` のみ — [layer-rule 15]
- `internal` の推移閉包に `repository` が現れない（backend 完結） — [code-rule 10]

## 主要コマンド

```bash
cd layered-checker

# 検証の実行
./verify.sh <input.json>

# 構文チェック
bash -n verify.sh
```

## ディレクトリ構成

```text
layered-checker/
├── CLAUDE.md          # AI エージェント向けの作業原則
├── verify.sh          # 検証スクリプト本体
└── sample/
    └── sample.json    # 入力 JSON の例
```
