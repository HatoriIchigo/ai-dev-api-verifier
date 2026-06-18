# ai-dev-api-verifier

**AI 駆動開発のガードレール**となる検証ツール群を収録したモノレポです。
AI がコード・仕様を量産していく開発スタイルでは一貫性が崩れやすいため、設計 JSON・OpenAPI 仕様書・
Java ソースを、定められた構成／規約どおりかを各段階で厳密に検証します。各ツールは CI ゲート
（成功=0 / 失敗=非0）として利用できます。

## 収録ツール

| ツール | 概要 | 技術 | 詳細 |
|--------|------|------|------|
| [`ifspec-verifier/`](ifspec-verifier/) | OpenAPI 仕様書（IF 仕様書）を検証。redocly lint ＋ 独自規約（`x-internal`・先頭フロントマター） | bash / redocly / yq | [docs/ifspec-verifier.md](docs/ifspec-verifier.md) |
| [`layered-checker/`](layered-checker/) | レイヤードアーキテクチャの元となる設計 JSON を検証（構成ルール・レイヤールール） | bash / jq | [docs/layered-checker.md](docs/layered-checker.md) |
| [`java-builder/`](java-builder/) | Java ソースを AST 解析し、構成・コーディング規約への適合を検証 | Java 21 / Maven / Docker | [docs/java-builder.md](docs/java-builder.md) |

ツール間の関係: `ifspec-verifier` が IF 仕様書（OpenAPI）を、`layered-checker` がその設計 JSON を、
`java-builder` が生成済みの Java 実装を検証します。

## クイックスタート

```bash
# OpenAPI 仕様書の検証
cd ifspec-verifier && ./verify.sh openapi.yaml

# 設計 JSON の検証
cd layered-checker && ./verify.sh sample/sample.json

# Java ソースの検証（Docker 経由。手順は docs/java-builder.md を参照）
```

## ディレクトリ構成

```text
.
├── README.md
├── CLAUDE.md              # AI エージェント向けの作業原則（全ツール共通）
├── docs/                  # ツールごとの詳細ドキュメント
│   ├── code-rule.md       # レイヤードアーキ構成規約の正本（layered-checker / java-builder 共通）
│   ├── ifspec-verifier.md
│   ├── layered-checker.md
│   └── java-builder.md    # java-builder/ 配下の詳細への入口
├── ifspec-verifier/       # OpenAPI 仕様書の検証ツール
├── layered-checker/       # 設計 JSON の検証ツール
└── java-builder/          # Java ソースの AST 検証ツール
```
