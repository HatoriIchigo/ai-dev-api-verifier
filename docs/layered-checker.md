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
- `description` の形式（`top` / `layer.*` / `internal.*` / `repository` の全定義に適用）:
  - 「**どういう入力で : どういう処理をして（概要） : 何が出力されるのか**」を**半角コロン `:`** で結んだ
    **ちょうど3要素**であること（区切りは半角 `:` のみ・各要素は空不可）。
  - **複数処理を持つ場合は文字列の配列**で表現できる。配列の各要素が上記3要素形式を満たすこと。
    例: `"ユーザID:アカウント情報を取得:アカウント詳細"` または
    `["ログイン要求:認証情報を検証:認証結果", "ユーザID:アカウント情報を整形:アカウント詳細"]`
  - `description` を省略可能なコンポーネント（`layer.*`・`internal.*`・`repository`）では、
    存在する場合のみ形式を検査する（`top` は従来どおり必須）。
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

## テスト

`tests/run_tests.sh` が単体テストランナーです（ifspec-verifier と同方式・並列実行対応）。
`tests/test_*/input.json` を `verify.sh` で検証し、期待結果と一致するかを確認します。

各ケースは次のファイルを持ちます。

- `input.json` … 検証対象の入力（**1ケースにつき1つの失敗要因**だけを仕込む）
- `expected` … 失敗すべき検証段を1語で指定する
  - `pass` … 検証成功（exit 0）
  - `json` … JSON 構文（`verify_json`）のみ失敗
  - `structure` … レイヤー構成（`verify_structure`）のみ失敗
  - `rules` … レイヤールール（`verify_rules`）のみ失敗
- `match`（任意） … 出力に含まれるべき部分文字列（1行1件）。発火したルールを厳密にピン留めする

`verify.sh` は最初に失敗した段で `exit` するため、ランナーは標準出力の `... OK` マーカーで
**どの段まで到達したか**を判定し、`expected` と照合します。`match` があれば、その全行が
出力に含まれることも追加で確認します。1つでも不一致なら終了コード 1 を返します。

> 注: `layer 連番` ルール（test_17）は、レイヤー番号に歯抜けを作ると構造上必ず
> 「下位レイヤー未 import」または「レイヤー差>=2」を併発するため、単一要因に分離できません。
> 当該ケースは `match` で連番ルールの発火のみをアサートしています。

```bash
cd layered-checker

# 全テスト実行（並列数は引数 or MAX_JOBS、既定 4）
./tests/run_tests.sh
./tests/run_tests.sh 8
MAX_JOBS=2 ./tests/run_tests.sh
```

### テストの追加方法

```bash
cd layered-checker
mkdir tests/test_NN
# 検証対象の JSON を配置（1ケース=1失敗要因になるよう作る）
$EDITOR tests/test_NN/input.json
# 期待する失敗段を記述（pass / json / structure / rules のいずれか）
echo structure > tests/test_NN/expected
# 任意: 発火させたいルールのメッセージ断片を1行1件で記述（誤検出防止のピン留め）
printf '%s\n' 'top[0] の description は「入力:処理:出力」' > tests/test_NN/match
```

既存ケースは `sample/sample.json` を `jq` で1箇所だけ加工して作っている。同じ方針で、
**狙ったルールだけ**を破る入力にすること（複数ルールを同時に破ると分離が崩れる）。

## 主要コマンド

```bash
cd layered-checker

# 検証の実行
./verify.sh <input.json>

# 構文チェック
bash -n verify.sh

# テスト（並列数は引数 or MAX_JOBS、既定 4）
./tests/run_tests.sh
./tests/run_tests.sh 8
MAX_JOBS=2 ./tests/run_tests.sh
```

## ディレクトリ構成

```text
layered-checker/
├── CLAUDE.md          # AI エージェント向けの作業原則
├── verify.sh          # 検証スクリプト本体
├── sample/
│   └── sample.json    # 入力 JSON の例
└── tests/
    ├── run_tests.sh   # 単体テストランナー（並列実行対応）
    └── test_*/        # 各ケース: input.json / expected / match(任意)
```
