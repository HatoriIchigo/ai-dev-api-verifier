---
name: backend-architecture
description: バックエンドの IF 仕様書（OpenAPI YAML）とレイヤードアーキテクチャ設計（JSON）を生成・更新する。バックエンドの API 設計／アーキテクチャ設計／IF 仕様書づくりを頼まれたとき、エンドポイントやレイヤー構成・repository・DTO を定義するとき、`backend.openapi.yaml` や `backend.architecture.json` を作る・直すときは必ずこのスキルを使う。成果物は ifspec-verifier・layered-checker の検証を通すことが必須なので、自己流で書かず references/ の生成要件に従うこと。
---

# backend-architecture

## 概要

バックエンドの設計成果物を **2 つ**生成する。両者は対であり、先に API の契約（IF 仕様書）を確定し、
それを満たすレイヤー構成を設計 JSON で表す、という順序で作る。

| 成果物 | 内容 | 検証ツール | 生成要件 |
|--------|------|-----------|---------|
| `docs/backend.openapi.yaml` | OpenAPI 形式の IF 仕様書（API 契約） | `ifspec-verifier` | [references/openapi-spec.md](references/openapi-spec.md) |
| `docs/backend.architecture.json` | レイヤードアーキテクチャ設計（実装の構成定義） | `layered-checker` | [references/architecture-json.md](references/architecture-json.md) |

**両ファイルは生成後に対応する検証ツールを必ず通す**（CI ゲートと同じ基準）。検証を通らないものは
未完成とみなし、終了コード 0 になるまで直す。各ファイルの詳細な生成要件・ルール・例・検証コマンドは
上表の references を読むこと（本ファイルは概要のみ）。

## ワークフロー

1. **要件整理** — エンドポイント（パス・メソッド・役割）と、各エンドポイントが外部接続を伴うか
   （DB・外部 API・認証基盤など）を洗い出す。外部接続の有無がレイヤー構成と `x-internal` を左右する。
2. **IF 仕様書を書く** — `docs/backend.openapi.yaml`。要件は [references/openapi-spec.md](references/openapi-spec.md)。
3. **設計 JSON に落とす** — `docs/backend.architecture.json`。OpenAPI の各 operation を `top` エントリに
   対応させ、その背後のレイヤー・repository・DTO を設計する。要件は
   [references/architecture-json.md](references/architecture-json.md)。
4. **検証する** — 両ファイルをそれぞれの検証ツールに通し、`[ERROR]` が出ず終了コード 0 になるまで直す。

既存ファイルがある場合は読み込んで差分更新する（全書き換えで既存の設計意図を壊さない）。

## 2 ファイル間の整合性

設計 JSON は IF 仕様書の写像なので、次を必ず一致させる。

- OpenAPI の `operationId` ↔ 設計 JSON の `top[].operationId`（1:1 対応）。
- OpenAPI の `x-internal: true`（外部接続あり）↔ JSON `top[].entry` が `top/Xxx`、`false`／省略（外部接続なし）↔ `internal/Xxx`。
- `internal`（backend 完結）のエンドポイントは repository に到達してはならない（外部接続なしの宣言と実態を一致させる）。
