# Java AST解析ツール

## 概要

本プロジェクトは、**AI駆動開発**においてJavaのコード構成・ディレクトリ構成を解析し、
あらかじめ定義された仕様（構成ルール）通りになっているかを厳密にチェックして、
コードを管理していくためのツールです。

AIがコードを生成・変更していく開発スタイルでは、コードの量産速度が上がる一方で、
構成の一貫性が崩れやすくなります。本ツールはその「ガードレール」として機能し、
コードベースが常に定められた構成・規約を満たしている状態を維持することを目的とします。

## 役割

本ツールの役割は主に2つです。

### 1. ディレクトリ構成管理

- Javaプロジェクトのディレクトリ構成（パッケージ構成）を解析する。
- あらかじめ定義された構成ルール（レイヤー構成・命名規約・配置ルールなど）と
  実際のディレクトリ／ファイル配置を突き合わせて検証する。
- 規約から逸脱したファイル・パッケージの配置を検出し、報告する。

### 2. コードチェック

- JavaソースコードをAST（抽象構文木）として解析する。
- クラス・メソッド・依存関係などが仕様・コーディング規約に沿っているかをチェックする。
- 違反箇所を検出し、どのルールにどう違反しているかを報告する。

## 検証仕様（構成ルール）

検証対象プロジェクトのルートを第1引数で指定する（省略時はカレントディレクトリ）。
違反があれば標準エラー出力に内容を出し、**exit code 1** で終了する（I/Oエラーは 2、正常は 0）。

> **検証は Docker で実行すること。** ローカルJVM・ビルド環境の差異を排除し、社内SSL傍受(Zscaler)
> 対応を含む再現可能な環境で実行するため、検証は必ず `Dockerfile` でビルドしたコンテナ経由で行う
> （ホストへの直接 `mvn` / `java -jar` 実行は行わない）。手順は下記「技術構成」を参照。

**構成ルールの詳細仕様はリポジトリ直下の [`docs/`](../docs/) に一元管理する**（`layered-checker` と共通の正本）。

- [構成ルール本体（docs/code-rule.md）](../docs/code-rule.md) — ディレクトリ構成・コード内容ルール（1〜10）・レイヤー依存／外部連携（11〜16）・整合性
- [java-builder 固有の実装詳細（docs/java-builder.md）](../docs/java-builder.md) — 検出方式・外部設定ファイル・シンボル解決の限界・IF 仕様書（OpenAPI）との `x-internal` 突合

## 実装クラス

- `Main` — エントリポイント。引数解釈（第1: 対象プロジェクトルート／第2: IF仕様書パス・任意）と終了コード制御。
- `DirectoryValidator` — プロジェクトルート〜`app/` の階層構成を検証し、各 `app/` に対して下記クラスを実行。
- `AppStructureValidator` — `app/` 配下の `.java` 配置・`layer` 連番・`dto/in`・`dto/out` 数を検証。
- `CodeRuleValidator` — JavaParser の AST を用いてコード内容ルール（内容ルール1〜10・レイヤー依存・外部連携）を検証。
- `OpenApiValidator` — IF仕様書（OpenAPI）の `x-internal` 宣言と `internal/`・`top/` 配置のゾーン整合を検証（IF仕様書指定時のみ）。
- `ProhibitedWordValidator` — `src/main/java` 全体に使用禁止語（dummy/mock/fake 等）が含まれないか検証。

## 技術構成

- **言語 / ビルド**: Java 21 / Maven (`pom.xml`)
- **AST解析**: JavaParser (`com.github.javaparser:javaparser-core`)
- **シンボル解決**: JavaParser Symbol Solver (`com.github.javaparser:javaparser-symbol-solver-core`) —
  クロスクラスの参照解決（値フロー検査で `constants/` 由来の値が外部連携へ流入していないか追跡）
- **IF仕様書(OpenAPI/YAML)解析**: SnakeYAML (`org.yaml:snakeyaml`)
- **エントリポイント**: `com.example.Main` (`src/main/java/com/example/Main.java`)
- **実行**: `maven-shade-plugin` で依存同梱の実行可能JAR (`app.jar`) を生成し、
  `java -jar app.jar <対象プロジェクトのルート> [IF仕様書パス]` で実行（第2引数は任意）
- **コンテナ**: `Dockerfile` によるビルド／実行環境。社内SSL傍受(Zscaler)対応のため、
  ビルドステージで `zscaler.crt` をJVMトラストストアに取り込む

## ディレクトリ構成

```
java-builder/
├── CLAUDE.md          # 本ファイル（プロジェクト指示書）
├── pom.xml            # Mavenビルド定義
├── Dockerfile         # コンテナビルド定義
├── .dockerignore
├── zscaler.crt        # 社内SSL傍受(Zscaler)対応のルートCA（Dockerビルドで使用）
│                      # ※ 構成ルールの詳細仕様はリポジトリ直下 ../docs/ に一元管理
├── examples/
│   └── openapi.yaml   # IF仕様書（OpenAPI）の例。x-internal による internal/外部の宣言例
└── src/
    └── main/
        ├── java/
        │   └── com/example/
        │       ├── Main.java                  # エントリポイント
        │       ├── DirectoryValidator.java    # ルート〜app/ の階層構成検証
        │       ├── AppStructureValidator.java # app/配下の配置・layer連番・dto数検証
        │       ├── CodeRuleValidator.java     # AST（JavaParser）によるコード内容検証
        │       ├── OpenApiValidator.java      # IF仕様書(OpenAPI)とのゾーン整合検証
        │       └── ProhibitedWordValidator.java # src/main/java 全体の使用禁止語検査
        └── resources/
            ├── external-packages.txt          # 外部連携パッケージの拒否リスト（既定値）
            ├── secret-keywords.txt            # 外部化すべき値の識別子キーワード（既定値）
            └── prohibited-words.txt           # main/java の使用禁止語（dummy/mock/fake 等）
```
