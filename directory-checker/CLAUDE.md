# directory-checker

## 概要

プロジェクトの**ディレクトリ／ファイル構成**を実ファイルツリーとして検証するツール。
旧 `java-builder` が担っていた構成検証（ディレクトリ階層・配置・命名・連番・ファイル種別）を移行し、
**構成検証の正本**とする。コード内容（AST 検査：文字列ハードコード／if-for 禁止／命名 import 解決など）は
引き続き `java-builder` 側が担う（役割分担）。

構成ルールの本体は [`docs/code-rule.md`](../docs/code-rule.md)「ディレクトリ構成ルール」に一元管理する
（`layered-checker`・`java-builder` と共通の正本）。詳細仕様は [`docs/directory-checker.md`](../docs/directory-checker.md)。

## 技術スタック

- bash（`find` / `grep` / `sed`）。jq は不要。

## 使い方

```sh
./verify.sh --dir <検証対象ディレクトリ> --type <iac|backend|frontend>
```

- `--dir` … 検証対象プロジェクトのルート（必須）
- `--type` … `iac` / `backend` / `frontend` のいずれか（必須）。**これ以外の値はエラー終了**。

終了コード: `0`=成功（または未対応スキップ） / `1`=構成違反・入力エラー。

## type ディスパッチ

今回は Java backend の移行のため **backend のみ実装**。

- `backend` … ビルドファイル（`pom.xml` / `build.gradle` 等）を検出したら **Java backend** として
  `backend/java/verify.sh` で構成検証する。Java と判定できなければ未対応としてスキップ（pass）。
- `iac` / `frontend` … 未実装。未対応としてスキップ（pass）。
- 上記以外の値 … **エラー終了（exit 1）**。

> 将来 `iac` / `frontend` や他言語の backend を追加する場合は、`verify.sh` の `case "$type"` 分岐と
> `backend/<lang>/verify.sh` 等を足す（ディレクトリで言語/種別を分割してよい）。

## ソースルートの解決

`<dir>` 配下のソースルートは固定 `src` ではなく解決する（`java-builder` と整合）。優先順位:

1. 環境変数 `JAVA_BUILDER_SRC_ROOT`
2. `<dir>/application.yaml`（または `.yml`）の top-level キー `src-root`
3. 既定値 `src`

※ application.yaml の読み取りは top-level の単一スカラ `src-root: <値>` のみ対応（簡易パーサ）。

## 構成

```text
directory-checker/
├── CLAUDE.md
├── verify.sh                  # エントリ（--dir/--type 解釈・type ディスパッチ・Java 検出）
├── backend/
│   └── java/
│       └── verify.sh          # Java backend のディレクトリ/ファイル構成検証
└── tests/
    ├── run_tests.sh           # フィクスチャ(test_*/project)を検証し expected と照合
    └── test_*/                # project/・expected・(type/env/mkdirs/match)
```

## テスト

```sh
./tests/run_tests.sh
```

`tests/test_*/` に検証対象ツリー `project/` と `expected`（`pass`/`fail`）を置く。任意で `type`（既定
backend）・`env`（KEY=VALUE）・`mkdirs`（空ディレクトリ）・`match`（出力に含むべき部分文字列）を併用する。
各異常系テストは **1つの失敗要因だけ**を発生させ、`match` で要因を固定する。
（git は空ディレクトリを追跡できないため、空ディレクトリ依存ケースは `mkdirs` で実行時に作成する。）
