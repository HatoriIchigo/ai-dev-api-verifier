# java-builder

Java のコード構成・ディレクトリ構成を **AST（抽象構文木）解析**し、あらかじめ定義された構成ルール
どおりかを厳密に検証するツールです。AI 駆動開発でコードの一貫性を保つ「ガードレール」として機能します。

[`layered-checker`](layered-checker.md) が設計 JSON を構造レベルで検証するのに対し、`java-builder` は
生成済みの **Java ソースそのもの**を AST で検証します（文字列ハードコード・if/for 禁止・サイズ制限など、
JSON からは判定できないルールを含む）。

## 構成ルール本体

レイヤー・repository などの**構成ルール本体は [`docs/code-rule.md`](code-rule.md) に一元管理**しています
（`layered-checker` と共通の正本）。`java-builder` はそのルールを AST レベルまで含めて全件検証します。
本ファイルでは、ルールを Java ソースで検証するうえでの **`java-builder` 固有の実装詳細**のみを補足します。

## 実行・技術構成

- **言語 / ビルド**: Java 21 / Maven。AST 解析は JavaParser、IF 仕様書（OpenAPI/YAML）解析は SnakeYAML。
- **クロスクラスの参照解決**: JavaParser Symbol Solver（ソースルート `src/main/java`）。
- **エントリポイント**: `com.example.Main`。`java -jar app.jar <対象プロジェクトのルート> [IF仕様書パス]`
  （第2引数の IF 仕様書は任意。`maven-shade-plugin` で依存同梱の実行可能 JAR を生成）。
- **実行は Docker 必須。** ローカル JVM・ビルド環境の差異を排除し、社内 SSL 傍受(Zscaler) 対応を含む
  再現可能な環境で実行するため、`Dockerfile` でビルドしたコンテナ経由で行う（ホストへの直接
  `mvn` / `java -jar` 実行は行わない）。ビルドステージで `zscaler.crt` を JVM トラストストアに取り込む。
- **引数**: 第1引数＝検証対象プロジェクトのルート（省略時カレント）、第2引数＝IF 仕様書パス（任意）。
- **終了コード**: 正常 0 / 違反 1 / I/O エラー 2。違反は標準エラー出力に内容を出す。

## 実装クラス

- `Main` — エントリポイント。引数解釈と終了コード制御。
- `DirectoryValidator` — プロジェクトルート〜`app/` の階層構成を検証し、各 `app/` に対し下記を実行。
- `AppStructureValidator` — `app/` 配下の `.java` 配置・`layer` 連番・`dto/in`・`dto/out` 数を検証。
- `CodeRuleValidator` — JavaParser の AST でコード内容ルール（1〜10、DTO の Lombok 必須=4.1 を含む）・レイヤー依存・外部連携を検証。
- `OpenApiValidator` — IF 仕様書（OpenAPI）の `x-internal` 宣言と `internal/`・`top/` 配置のゾーン整合を検証（IF 仕様書指定時のみ）。
- `ProhibitedWordValidator` — `src/main/java` 全体に使用禁止語が含まれないか検証。

## ルール実装の補足（[code-rule.md](code-rule.md) の検出詳細）

### 外部化すべき値のハードコード検出（コード内容ルール 1）

2方式の併用で検出する。

- **名前ベース**: 定数名が外部設定 `secret-keywords.txt`（既定: シークレット系 `password`/`secret`/`token`/`apiKey` 等、
  ユーザ名系 `username`/`userId` 等、DB 系 `jdbc`/`datasource`/`dbHost`/`schema` 等、URL 系 `url`/`endpoint`/`host` 等。
  大文字小文字無視の部分一致）に該当し、かつ文字列リテラルで初期化されている場合エラー。
  **検査対象は `constants/`・`validation/` のみ**（他レイヤーは文字列リテラル自体がルール1で禁止のため重複検査しない）。
- **値ベース**: 値が既知の外部化対象形式（PEM 秘密鍵ヘッダ、AWS アクセスキー `AKIA…`/`ASIA…`、GitHub PAT `ghp_…`、
  Slack トークン `xox?-…`、JDBC `jdbc:…`、URL `scheme://…`）に一致する場合エラー（全レイヤー対象）。
- 読み込み優先順位: `-Dsecret.keywords=<path>` → カレントの `secret-keywords.txt` →
  JAR 同梱 `src/main/resources/secret-keywords.txt`（再ビルドなしで差し替え可能）。

### 使用禁止語（コード内容ルール 1.1）

- 禁止語は外部設定 `prohibited-words.txt`。読み込み優先順位:
  `-Dprohibited.words=<path>` → カレントの `prohibited-words.txt` → JAR 同梱 `src/main/resources/prohibited-words.txt`。

### 外部連携パッケージの拒否リスト（レイヤールール 15）

- 「外部連携」とみなすパッケージ接頭辞は外部設定 `external-packages.txt`（1行1接頭辞、`#` コメント可）で定義。
- 読み込み優先順位: `-Dexternal.packages=<path>` → カレントの `external-packages.txt` →
  JAR 同梱 `src/main/resources/external-packages.txt`（再ビルドなしで差し替え可能）。

### 外部連携へ流れる値の外部化（レイヤールール 16・値フロー検査）

- クロスクラスの参照解決には JavaParser の `JavaSymbolSolver`（ソースルート `src/main/java`）を使う。
  シンボル解決が構成できない場合は当該引数の判定をスキップする（誤検知を出さない方針）。
- **外部クライアント（シンク）の判定**: レシーバが外部連携 import の型を宣言型に持つ変数／フィールド、
  または外部型の静的呼び出し・`new 外部型(..)`。
- **v1 の追跡範囲（既知の限界）**: 「`constants` の定数 → repository での使用（直接／同一クラス内の別名）」を
  追跡する。レイヤーをまたいでメソッド引数として渡ってきた値までは辿らない。変数に束ねず連鎖呼び出しする
  外部クライアント（`X.builder().build().call(..)`）はレシーバ型を解決できないため対象外。

## IF 仕様書（OpenAPI）との突合

エンドポイントが「外部接続を伴うか（通常）／backend 完結か（internal）」を IF 仕様書（OpenAPI 形式）で
宣言し、その宣言とコード実態を突き合わせて検証する（declare + verify）。IF 仕様書のパスは第2引数で指定する
（例の仕様書を `examples/openapi.yaml` に同梱）。

- **宣言**: operation レベルのベンダ拡張 `x-internal` で宣言する。
  - `x-internal: true` … 外部接続あり。エントリクラスは `app/top/` に置く。
  - `x-internal` 省略／`false` … 外部接続なし（backend 完結）。エントリクラスは `app/internal/`。
- **マッピング**: `operationId` をエントリクラス名（PascalCase）に対応付ける
  （例: `checkHealth` → `internal/CheckHealth.java`、`loginAccount` → `top/LoginAccount.java`）。
- **判定ロジック**:
  1. ゾーン整合: `x-internal: true` のエントリは `top/` に、省略/false のエントリは `internal/` に存在すること。
  2. internal の外部到達禁止（[code-rule.md](code-rule.md) ルール 10）: internal エントリの import 推移閉包に
     `repository`／外部連携パッケージが現れたらエラー（＝internal 宣言が嘘でないことを保証）。
  3. 外部エントリは従来どおり `top → layer → repository` で外部連携する（ルール 3/7/8/15）。

  ※ OpenAPI の標準フィールドからは外部接続要否を推論できないため、`x-internal` による明示宣言を正とし、
    コード側の到達可能性で検証する。

## ディレクトリ構成

```text
java-builder/
├── CLAUDE.md          # プロジェクト指示書（概要・役割・実装クラス・技術構成）
├── pom.xml            # Maven ビルド定義
├── Dockerfile         # コンテナビルド定義
├── .dockerignore
├── zscaler.crt        # 社内 SSL 傍受(Zscaler) 対応のルート CA（Docker ビルドで使用）
├── examples/
│   └── openapi.yaml   # IF 仕様書（OpenAPI）の例。x-internal による internal/外部の宣言例
└── src/
    └── main/
        ├── java/com/example/   # Main / 各 Validator
        └── resources/          # external-packages.txt / secret-keywords.txt / prohibited-words.txt
```
