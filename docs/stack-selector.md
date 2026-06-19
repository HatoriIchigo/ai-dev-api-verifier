# stack-selector 詳細仕様

`stack-selector/` は「決定された技術スタック」を表す JSON ファイルの**キー構造**を検証する
bash + jq 製のスクリプトである。値の内容は問わず、**キーがそろっているか**のみを検証する。

- ツール概要・使い方: [stack-selector/CLAUDE.md](../stack-selector/CLAUDE.md)

## 検証内容

| 対象 | 必須キー | 補足 |
| -- | -- | -- |
| トップレベル | `language` / `package` / `build` / `libraries` | 未知キーもエラー |
| `language` | `kind` / `version` | オブジェクトであること。未知キーもエラー |
| `libraries` | （配列） | 空配列は許可 |
| `libraries[]` | `name` / `version` | オブジェクトであること。未知キーもエラー |

- **キー検証では値を見ない**: キー集合の一致のみを判定する（不足キー・未知キーの双方をエラー）。
- **段階**: `JSON 構文` → `キー検証` の順に検証し、最初に失敗した段で `exit 1` する。

## 構成妥当性チェック（助言・非ゲート）

キー検証の後に、`language.kind` と `package` / `build` の組み合わせが既知の妥当な構成かを
助言として確認する（`package`・`build` 双方に適用）。

| language.kind | 許可ツール |
| -- | -- |
| `java` | `maven` / `gradle` / `ant` |
| `python` | `pip` |
| `typescript` | `npm` |
| `go` | `go` / `gomod` |
| `rust` | `cargo` |

- **組み合わせは強制しない**（業務判断）。非許可ツール（python+`uv` 等）でも、
  未知言語でも**エラーにせず exit 0**。INFO で「妥当／一致せず（許容）／対象外」を知らせるのみ。
- 終了コードを左右するのはキー検証だけ。CI で組み合わせも落としたい場合は MISMATCH を
  `exit 1` 化する拡張（`--strict` 相当）が必要（現状未実装）。

## 入力例

```json
{
  "language": { "kind": "java", "version": "21" },
  "package": "maven",
  "build": "maven",
  "libraries": [
    { "name": "lombok", "version": "3.0.0" },
    { "name": "aaa", "version": "21" }
  ]
}
```

```json
{
  "language": { "kind": "python", "version": "3.12.3" },
  "package": "pip",
  "build": "uv",
  "libraries": [
    { "name": "tqdm", "version": "3.0.0" }
  ]
}
```

## テスト

`stack-selector/tests/run_tests.sh` で実行する。`expected`（`pass`/`fail`）と任意の `match`
（出力に含むべき部分文字列）で検証し、各異常系は1つの失敗要因だけを発生させる。
正常系（java / python / 空 libraries）と、不足キー・未知キー・型不正・JSON 構文エラーの
異常系を網羅する。
