# directory-checker

プロジェクトの**ディレクトリ／ファイル構成**を実ファイルツリーとして検証するツール。
旧 `java-builder` が AST 解析の前段で行っていた構成検証（`DirectoryValidator`・`AppStructureValidator`・
統合テストディレクトリ存在＝ルール17の構造部分）を移行し、**構成検証の正本**とする。
コード内容（AST 検査）は引き続き [`java-builder`](java-builder.md) が担う（役割分担）。

構成ルール本体は [`docs/code-rule.md`](code-rule.md)「ディレクトリ構成ルール」に一元管理する
（`layered-checker`・`java-builder` と共通の正本）。本ファイルは `directory-checker` 固有の実装詳細を補足する。

## 実行・技術構成

- **言語**: bash（`find` / `grep` / `sed`）。jq は不要。
- **エントリ**: `verify.sh`。引数で種別を分岐し、Java backend は `backend/java/verify.sh` に委譲する。
- **終了コード**: 正常 0 / 構成違反・入力エラー 1。違反内容は stderr に出力する。

## 引数

```sh
./verify.sh --dir <検証対象ディレクトリ> --type <iac|backend|frontend>
```

- `--dir <dir>` … 検証対象プロジェクトのルート（必須・存在するディレクトリ）。
- `--type <type>` … `iac` / `backend` / `frontend` のいずれか（必須）。
  - **これ以外の値はエラー終了（exit 1）**。`--type` 省略もエラー。

## type ディスパッチ

今回は Java backend の移行のため **backend のみ実装**している。

| type | 挙動 |
| -- | -- |
| `backend` | `pom.xml` / `build.gradle`(.kts) / `settings.gradle`(.kts) を検出したら **Java backend** として `backend/java/verify.sh` で構成検証。検出できなければ未対応としてスキップ（pass） |
| `iac` / `frontend` | 未実装。未対応としてスキップ（pass） |
| 上記以外 | **エラー終了（exit 1）** |

> 「`javaファイルの時のみ検証`」の方針に従い、backend はビルドファイルの有無で Java を判定する。
> 将来 `iac` / `frontend` や他言語 backend を追加する場合は `verify.sh` の `case "$type"` 分岐と
> `backend/<lang>/` 等を足す（ディレクトリで種別/言語を分割してよい）。

## Java backend の検証内容（`backend/java/verify.sh`）

[`code-rule.md`](code-rule.md)「ディレクトリ構成ルール」のうち、実ファイルツリーから判定できるものを検証する。

- **D1 ルート構成**: `<src>/main/java/com/<projectName>/app/` 構成。`<projectName>` は `^[0-9a-zA-Z_-]+$`。
- **ファイル種別配置**: `<src>/{main,test}/java` は `.java` のみ、`<src>/{main,test}/resources` は `.yaml`/`.yml` のみ（大文字小文字無視）。対象ディレクトリが無ければスキップ。
- **D2 app/ 配下配置**: app 直下は `Application.java` のみ。許可ディレクトリ
  （`top`/`internal`/`layer<数値>`/`repository`/`dto/in`/`dto/out`/`log`/`util`/`validation`/`constants`）以外に `.java` を置けない。
- **D3**: `dto/in` と `dto/out` の `.java` 件数一致。
- **D4**: `layer<数値>` は1始まりの連番（歯抜け不可）。
- **D5 命名サフィックス**: `repository/*.java`=`*Repository`、`dto/in/*.java`=`*InDto`、`dto/out/*.java`=`*OutDto`。
- **ルール17（構造部分）**: `<src>/test/java/com/<projectName>/integration/` が存在し `.java` を1つ以上含む。
  - エンドポイント網羅（ルール18）は OpenAPI が必要なため `directory-checker` の対象外（`java-builder` が担う）。

### ソースルートの解決（`<src>`）

固定 `src` ではなく解決する（`java-builder` の `BuildConfig` と整合）。優先順位:

1. 環境変数 `JAVA_BUILDER_SRC_ROOT`
2. `<dir>/application.yaml`（または `.yml`）の top-level キー `src-root`
3. 既定値 `src`

※ application.yaml の読み取りは top-level の単一スカラ `src-root: <値>` のみ対応（簡易パーサ。複雑な YAML 構造は未対応）。
`java-builder` が application.yaml をカレントディレクトリ基準で読むのに対し、本ツールは検証対象 `<dir>` 直下を読む
（プロジェクト自身の設定として自然なため）。環境変数名・キー名・既定値は揃えてある。

## テスト

```sh
./tests/run_tests.sh
```

`tests/test_*/` に検証対象ツリー `project/` と `expected`（`pass`/`fail`）を置く。任意で
`type`（既定 backend）・`env`（KEY=VALUE）・`mkdirs`（空ディレクトリ）・`match`（出力に含むべき部分文字列）。
各異常系は **1つの失敗要因だけ**を起こし `match` で要因を固定する。正常系（valid backend・未対応スキップ・
src-root 可変）も併せて網羅する。git は空ディレクトリを追跡できないため、空ディレクトリ依存ケースは
`mkdirs` で実行時に作成する（フィクスチャは一時コピーに対して検証し、リポジトリ内を汚さない）。
