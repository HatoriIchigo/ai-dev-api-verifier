# コードルール（レイヤードアーキテクチャ構成規約）

レイヤードアーキテクチャの**構成・依存・配置ルール**を一元管理する正本（カタログ）です。
設計 JSON と Java 実装の双方が満たすべき規約を、ツール非依存で番号付き定義します。

## 検証主体（どのツールがどう担保するか）

同じルールを、対象成果物に応じて 2 つのツールが別レベルで検証します。

| ツール | 対象 | レベル | 詳細 |
|--------|------|--------|------|
| [`layered-checker`](layered-checker.md) | 設計 JSON | **構造**から判定できるルールのみ | `verify.sh`（bash / jq） |
| [`java-builder`](java-builder.md) | Java ソース | **AST**まで含む全ルール | Docker / JavaParser |

- `layered-checker` が担保するのは構造的に判定可能なルール（後述の「構造判定可」印あり）。
  文字列ハードコード・if/for 禁止・サイズ制限など AST が必要なルールは対象外で、`java-builder` が担う。
- `java-builder` の AST 実装に固有の詳細（検出方式・外部設定ファイル・シンボル解決の限界・
  OpenAPI との突合など）は [`docs/java-builder.md`](java-builder.md) に置く。本ファイルはルール本体の正本とする。

---

## ディレクトリ構成ルール

### プロジェクトルート構成

```
src/main/java/com/<projectName>/app/
```

- D1. `<projectName>` は `^[0-9A-Za-z-_]+$`（英数字・ハイフン・アンダースコア）にマッチすること。【構造判定可】
- 上記構成でなければエラー終了。

### ソースツリーのファイル種別（main / test 共通）

- `src/main/java/`・`src/test/java/` 配下には `.java` ファイルのみ配置できる。
- `src/main/resources/`・`src/test/resources/` 配下には `.yaml`・`.yml` ファイルのみ配置できる。
- 対象ディレクトリが存在しない場合はスキップ。許可外の拡張子があればエラー。

### app/ 配下の構成

| パス | 概要 |
| -- | -- |
| `Application.java` | メイン（app 直下に置けるのはこのファイルのみ） |
| `top/*.java` | 最上位層（外部接続ありエンドポイントのエントリ） |
| `internal/*.java` | backend 完結エンドポイントのエントリ（外部接続なし。repository/外部連携への到達禁止） |
| `layer<数値>/*.java` | サービス層（`^layer[0-9]+$`、複数可。1始まりの連番であること） |
| `repository/*.java` | 外部ツールとの連携 |
| `dto/in/*.java` | in 側の DTO |
| `dto/out/*.java` | out 側の DTO |
| `log/*.java` | ログ関連 |
| `util/*.java` | util ツール |
| `validation/*.java` | バリデーション関連 |
| `constants/*.java` | 定数 |

- D2. 上記以外の場所に `.java` ファイルがあればエラー。
- D3. `dto/in` と `dto/out` の `.java` ファイル数が一致しなければエラー。【構造判定可】
- D4. `layer<数値>` は1始まりの連番であること。歯抜け（例: layer1〜4, layer6〜7 で layer5 が欠番）はエラー。【構造判定可】

---

## コード内容ルール（AST 検査）

1. **文字列ハードコードの集約**: 文字列リテラルは `constants/*.java` と `validation/*.java` のみ許可。
   それ以外の場所（`top` / `layer*` / `repository` / `dto/**` / `log` / `util` / `Application.java`）に
   文字列リテラルがあればエラー（アノテーション引数の文字列も対象）。
   - **SQL 文字列の禁止（例外なし）**: `SELECT` / `INSERT` / `UPDATE` / `DELETE` / `MERGE` /
     `CREATE` / `DROP` / `ALTER` / `TRUNCATE` で始まる文字列リテラルは、`constants/`・`validation/`
     を含む**すべての場所で禁止**。生 SQL を集約するのではなく、SQL mapper（MyBatis / JPA 等）の
     仕様を優先すること。
   - **外部化すべき値のハードコード禁止（例外なし）**: シークレット（パスワード・トークン・API キー等）に
     加え、**ユーザ名系・DB 接続情報系・URL 系**といった環境依存の設定値は、`constants/`・`validation/` を
     含む**すべての場所で禁止**。`application.yaml` + 環境変数経由で注入すること。
     （名前ベース／値ベースの検出方式・外部設定ファイルは [docs/java-builder.md](java-builder.md) を参照）

1.1. **使用禁止語（テストダブル混入防止）**: `src/main/java` 配下（本番コード**全体**）では
   `dummy` / `mock` / `fake` / `stub` 等の語を使用禁止（大文字小文字無視の部分一致。クラス名・メソッド名・
   変数名・文字列・コメント等ソーステキスト全体が対象）。1つでも現れればエラー。`src/test` 配下は対象外。
   （禁止語の外部設定は [docs/java-builder.md](java-builder.md) を参照）

1.2. **非決定的な乱数・ID 生成の禁止（例外なし・全レイヤー）**: その場で非決定的に値を生成すると、
   本来は呼び出し元・永続化層から受け取る／注入すべき値を握りつぶし、「無理やりテストを通す」実装や
   再現性の低下を招く。ID は呼び出し元から受け取るか ID 生成器を注入し、乱数が必要な場合は乱数源を
   注入する。検出対象:
   - `UUID.randomUUID()`（FQN `java.util.UUID.randomUUID()` / static import 経由の `randomUUID()` 含む）
   - `Math.random()`（FQN `java.lang.Math.random()` / static import 経由の `random()` 含む）
   - `new Random(...)`（`java.util.Random`）

   セキュリティ用途の乱数は `SecureRandom` を使うこと（**本ルールの対象外**）。
   AST 検査のため `java-builder` のみで判定する。

1.3. **constants の定数定義制約（例外なし）**: `constants/*.java` は環境非依存の固定値だけを
   素朴な定数として公開する。動的に組み立てた値や DTO への依存を持ち込まない。次をすべて満たすこと:
   - (a) **DTO の import 禁止**: `com.<projectName>.app.dto`（`dto.in` / `dto.out` を含む）を
     import してはならない。定数は DTO に依存しない（依存方向は DTO → constants ではなく逆も不可）。
   - (b) **`public static final` 必須**: フィールドはすべて `public static final` で宣言する。
     `public` / `static` / `final` のいずれかを欠くフィールドがあればエラー。
   - (c) **型は String とプリミティブのみ**: `String` と 8 プリミティブ
     （`int` / `long` / `short` / `byte` / `char` / `boolean` / `float` / `double`）のみ許可。
     `List` / `Map` / 配列 / その他オブジェクト型はエラー。
   - (d) **初期化子はリテラルのみ**: メソッド呼び出し（`List.of(..)` / `Arrays.asList(..)` 等）や
     `new ...()` による生成での初期化は禁止。リテラル（リテラル同士の連結 `"a" + "b"` を含む）と
     他定数の参照（同クラス／別 constants クラスのフィールド参照）のみ許可。

   AST 検査のため `java-builder` のみで判定する。

1.4. **`localhost` のハードコード禁止（例外なし・main/test 全体）**: すべての `.java`
   （`src/main/java`・`src/test/java` の双方）で文字列 `localhost`（大文字小文字無視の部分一致。
   コメント・識別子・文字列リテラル等ソーステキスト全体が対象）を使用禁止。接続先（ホスト）は環境依存の
   設定値であり、`application.yaml` + 環境変数経由で注入すること。テストダブル禁止語（ルール1.1）が
   `main` 限定なのに対し、本ルールは **test も含む全 `.java`** を対象とする。テキスト走査で判定し、
   `java-builder` のみで検査する。

2. **処理の禁止**: `dto/**/*.java`・`repository/*.java`・`constants/*.java` では条件・繰り返し処理
   （`if` / `for` / 拡張 `for` / `while` / `do-while` / `switch` 文・式 / 三項演算子）を禁止。
3. **layer1 は repository 利用必須**: `layer1/` の各クラスは repository パッケージ
   （`com.<projectName>.app.repository`）を import していること。【構造判定可】
4. **repository と DTO の対応**: `repository/Foo.java` には同名の `dto/in/Foo.java` と
   `dto/out/Foo.java`（ベース名完全一致）が対応して存在すること（imports も dto/in・dto/out を各1件含む）。【構造判定可】
4.1. **DTO は Lombok 必須**: `dto/**` の各クラスは Lombok アノテーション（`@Data` / `@Value` /
   `@Getter` / `@Setter` / `@Builder` / `@*ArgsConstructor` / `@EqualsAndHashCode` / `@ToString` /
   `@With` / `@Accessors` のいずれか）を**少なくとも1つ**付与し、かつファイルが `lombok` パッケージを
   **import** していること（`import lombok.*;` / `import lombok.Data;` のいずれも可）。getter/setter・
   コンストラクタ等のボイラープレートは手書きせず Lombok に委ねる（推奨は `@Data` / `@Value`）。
   record / enum / interface は対象外（record は accessor を言語が生成するため）。
   AST 検査のため `java-builder` のみで判定する。
4.2. **repository のインメモリ実装の禁止＝外部連携 import 必須（例外なし）**: `repository/*.java` は
   外部ツール連携（DB・外部接続・AWS 等）を実際に行う層である。AI 生成では実連携を避け、
   `HashMap` 等の**インメモリの偽データストア**で「それっぽく」通す実装が混入しやすい（ルール 1.1
   テストダブル禁止・5.1 null 禁止と同種の握りつぶし）。これを防ぐため、`repository/` の各ファイルは
   外部連携パッケージ（拒否リスト `external-packages.txt`。ルール15と同じ正本）を**最低1つ import**
   していること。1つも無ければエラー。**ルール15（外部 import は repository でのみ許可）の裏返し**で、
   「repository 以外では外部連携禁止／repository では外部連携必須」を両面で担保する。
   想定する連携スタイルは**ファイル単位の直接連携**（各 `repository/Foo.java` が JDBC/SDK/HTTP 等を
   直接呼ぶ）。import 判定のため `java-builder` のみで実装する（構造判定可だが現状 `layered-checker`
   は未対応）。
4.3. **repository のコレクション state フィールド禁止（例外なし）**: `repository/*.java` は外部への
   パススルーであり、永続状態をプロセス内に溜め込んではならない。コレクション型
   （`Map` / `List` / `Set` / `Collection` 系および**配列**）を**インスタンス／static フィールド**として
   保持するとエラー（インメモリ偽データストアの典型症状を直接弾く）。メソッド内のローカル変数・引数は
   対象外。AST 検査のため `java-builder` のみで判定する。
5. **固定値 return の禁止**: リテラル（文字列・数値・真偽値・文字、符号付き数値含む）を直接返す
   `return` を禁止。`constants/`・`validation/` は対象外。`return null;` の扱いはルール 5.1 に従う。
5.1. **null の使用制限（例外なし・全レイヤー）**: `null` リテラルは原則禁止し、次の2つだけ許可する。
   - `==` / `!=` による null 比較（`if (x == null)`・`while (x != null)` 等、場所を問わず）
   - **catch ブロック内**の `return null;`

   これ以外（代入 `x = null`、変数・フィールドの初期化 `String s = null;`、関数・コンストラクタ引数
   `foo(null)` / `new Foo(null)`、catch 外の `return null;`、三項の null 枝など）はすべて禁止。
   null を撒くと NPE の温床になり未実装の握りつぶしにも使われるため、不在は Optional / 例外 / 既定値で
   表現する。AST 検査のため `java-builder` のみで判定する。
6. **サイズ制限**: 1ファイル 500 行以内、1メソッド／コンストラクタ 100 行以内（全 `.java` 対象）。
7. **下位レイヤー依存**: `layer<N>`（N≥2）は下位レイヤー（`layer1`〜`layer<N-1>`）の
   いずれかを import していること（例: `layer3` は `layer2` か `layer1` を import）。【構造判定可】
8. **top の import 制限**: `top/*.java` は最大番号のレイヤー（`layer<最大>`）のみ import 可能。
   それ以外のレイヤーを import するとエラー。【構造判定可】
9. **util の import 制限**: `util/*.java` は `layer<数値>`・`top`・`repository` パッケージを
   import 不可（util は下位の汎用ツールであり、上位・連携レイヤーへ依存してはならない）。
10. **internal の外部到達禁止**: `internal/*.java` は backend 完結（外部接続なし）のエントリ。
    そのエントリから推移的に辿った import 閉包に `repository` パッケージまたは外部連携パッケージ
    （拒否リスト）が含まれてはならない。【構造判定可】

---

## レイヤー依存・外部連携ルール

レイヤー番号付きクラス（`layer<N>/*.java`）同士の import 依存グラフを解析する。

11. **飛び越し参照の禁止**: `C → B`（`B` が下位レイヤー）で、間のレイヤー（`B` より上・`C` より下）の
    クラス `A` が既に `B` を import している場合、`C` は `B` を直接 import できない（`A` を経由する）。
    ※ `A` が `B` を import していなければ、この規則では制限しない。
12. **同一レイヤーの依存包含**: 同レイヤーの2クラスで、一方の依存集合がもう一方を包含する場合、
    包含する側を昇格し、被包含クラスを import して利用する（重複する直接 import を禁止）。
13. **同一レイヤーの依存重複**: 同レイヤーの2クラスが依存集合を部分的に共有する場合、
    共通依存を制御する新クラスへ切り出し、両者を昇格して共通依存の直接 import を禁止する。
14. **レイヤー差の制限**: `X → Y` で `layer(X) - layer(Y) >= 2` の場合、`X` を `layer(Y)+1` へ降格する
    （基準は下位レイヤー）。【構造判定可】
15. **外部連携は repository のみ**: DB・外部接続・AWS（Cognito 等）といった外部ツール連携を表す
    パッケージは、`repository/*.java` でのみ import 可能。それ以外のレイヤーで import するとエラー。
    （「外部連携」とみなすパッケージ接頭辞は拒否リストで定義。詳細は
    [docs/java-builder.md](java-builder.md) を参照）【構造判定可（`external` の所在）】
16. **外部連携へ流れる値の外部化（値フロー検査）**: `repository/*.java` の外部クライアント呼び出し／生成に
    渡る引数が `constants/` 由来の値であればエラー。接続情報・シークレットは `constants/` ではなく
    `application.yaml` + 環境変数経由で注入すること。
    （クロスクラスの参照解決・v1 の追跡範囲の限界は
    [docs/java-builder.md](java-builder.md) を参照）

---

## テスト構成ルール（java-builder のみ）

統合テストの存在と、IF 仕様書（OpenAPI）エンドポイントの網羅を検証する。test ツリーが対象のため
`java-builder` のみで判定する（`layered-checker` は設計 JSON のみを見るため対象外）。

17. **統合テストの必須化（常に検査）**: 各プロジェクトは `src/test/java/com/<projectName>/integration/`
    を持ち、配下に `.java` ファイルが1つ以上存在すること。ディレクトリが無い／`.java` が無ければエラー。
    OpenAPI 未指定でも必須。
18. **エンドポイントのハードコード網羅（OpenAPI 指定時のみ）**: IF 仕様書（OpenAPI）が指定された場合、
    `paths` の各エンドポイント文字列（例 `/accounts/login`）が `integration/` 配下のいずれかの `.java` に
    **リテラルとして現れる**こと。現れないエンドポイントがあればエラー。
    - 照合は path 文字列の**そのまま部分一致**。パスパラメータを含む path（例 `/users/{id}`）は
      テンプレートのまま照合するため、テスト側が具体値（`/users/123`）で書くと網羅とみなされない（既知の限界）。
19. **constants の定数値とテストリテラルの重複禁止（答え合わせ漏洩の検出）**: `constants/` は文字列リテラルが
    唯一許される場所（ルール1）で、かつ定数参照の `return` は固定値 return 禁止（ルール5）の対象外でもある。
    このため**テストの期待値を `constants/` に逃がし、layer から `return Const.X;` で返して「無理やりテストを
    通す」抜け道**が成立しうる（ルール 1.1・4.2/4.3 と同種の握りつぶし）。これを抑止するため、`src/main` の
    `constants/` で定義された**文字列定数の値**が `src/test` に**リテラルとして重複**して現れたらエラーとする。
    テストは定数を import して参照すべきで、同じ値を main と test の双方にベタ書きするのは「期待値を両側に焼き込む」
    ズルの署名である。
    - **誤検知の抑制**: String 定数のみ対象／長さ4文字未満（既定。`-Dconstants.testdata.minlen` で調整可）の値は
      除外／照合は文字列値の完全一致（識別子の部分一致ではない）。
    - **既知の限界**: (a) リテラル連結 `"a" + "b"` で組み立てた定数は連結後の値で照合しない。
      (b) `validation/`（エラーメッセージのテスト側ベタ書きで誤検知が多い）は意図的に対象外で `constants/` のみ検査する。
    - これは**完全検出ではなくズルのコストを上げる補助検査**である。真のバックストップは、結合テスト（ルール17/18）が
      実 repository・外部連携を通すことにある（constants 経由のズルは「外部に触れずに通る」ことで成立するため、
      統合テストの厳格運用が本筋）。AST 検査かつ test ツリー参照のため `java-builder` のみで判定する。

---

## 整合性ルール（参照解決）

- R1. `import` 宛先の実在（dangling 参照禁止）: `top` / `layer.*` / `internal.*` / `repository` の
  各 `imports` が指す `<zone>/<Name>` は、対応するコンポーネントとして実在すること。【構造判定可】
