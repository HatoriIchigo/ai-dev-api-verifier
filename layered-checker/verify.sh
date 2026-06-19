#!/usr/bin/env bash
#
# verify.sh
#
# レイヤーアーキテクチャの元となる JSON ファイルをチェックするスクリプト。
# jq が未インストールの場合は自動でインストールを試みる。
#
# 使い方:
#   ./verify.sh <input.json>
#
set -euo pipefail

# ---------------------------------------------------------------------------
# ログ出力
# ---------------------------------------------------------------------------
info()  { printf '\033[36m[INFO]\033[0m  %s\n' "$*"; }
warn()  { printf '\033[33m[WARN]\033[0m  %s\n' "$*" >&2; }
error() { printf '\033[31m[ERROR]\033[0m %s\n' "$*" >&2; }

# ---------------------------------------------------------------------------
# jq の存在確認とインストール
# ---------------------------------------------------------------------------
install_jq() {
  info "jq が見つかりません。インストールを試みます..."

  # root 以外、かつ sudo がある場合のみ sudo を付与する
  local SUDO=""
  if [[ "$(id -u)" -ne 0 ]] && command -v sudo >/dev/null 2>&1; then
    SUDO="sudo"
  fi

  # OS / パッケージマネージャを判定してインストール
  if command -v apt-get >/dev/null 2>&1; then
    $SUDO apt-get update && $SUDO apt-get install -y jq
  elif command -v dnf >/dev/null 2>&1; then
    $SUDO dnf install -y jq
  elif command -v yum >/dev/null 2>&1; then
    $SUDO yum install -y jq
  elif command -v apk >/dev/null 2>&1; then
    $SUDO apk add --no-cache jq
  elif command -v pacman >/dev/null 2>&1; then
    $SUDO pacman -Sy --noconfirm jq
  elif command -v zypper >/dev/null 2>&1; then
    $SUDO zypper install -y jq
  elif command -v brew >/dev/null 2>&1; then
    brew install jq
  else
    error "対応するパッケージマネージャが見つかりませんでした。手動で jq をインストールしてください: https://jqlang.github.io/jq/"
    exit 1
  fi
}

ensure_jq() {
  if command -v jq >/dev/null 2>&1; then
    return 0
  fi

  install_jq

  if ! command -v jq >/dev/null 2>&1; then
    error "jq のインストールに失敗しました。"
    exit 1
  fi
  info "jq のインストールが完了しました (version: $(jq --version))"
}

# ---------------------------------------------------------------------------
# JSON ファイルの検証
# ---------------------------------------------------------------------------
verify_json() {
  local file="$1"

  if [[ ! -f "$file" ]]; then
    error "ファイルが存在しません: $file"
    exit 1
  fi

  # JSON 構文として妥当かチェック
  if ! jq empty "$file" >/dev/null 2>&1; then
    error "JSON の構文が不正です: $file"
    jq empty "$file" || true
    exit 1
  fi

  info "JSON 構文 OK: $file"
}

# ---------------------------------------------------------------------------
# レイヤー構成の検証
#   - top / layer / repository / dto が必須
#   - layer のキーは layer<数値> 形式
#   - top の各エンドポイントに path / method / description / imports 必須
#   - dto 以外の各コンポーネント定義に imports 必須
#   - description は「入力:処理:出力」を半角コロンで結んだ3要素（複数処理は配列で表現可）
# ---------------------------------------------------------------------------
verify_structure() {
  local file="$1"
  local errors

  errors="$(jq -r '
    # description の値（文字列 または 文字列配列）が
    # 「入力:処理:出力」を半角コロンで結んだ3要素であるかを検査し、
    # 違反メッセージをストリームとして返す。
    #   - 半角コロン ":" のみを区切りとして許可（3分割を厳守）
    #   - 各要素は空であってはならない（[^:]+ により担保）
    #   - 配列の場合は各要素が同じ形式を満たすこと（複数処理対応）
    def descErrors($loc):
      if type == "string" then
        ( select( test("^[^:]+:[^:]+:[^:]+$") | not )
          | "\($loc) の description は「入力:処理:出力」を半角コロンで結んだ3要素にしてください: \(.)" )
      elif type == "array" then
        ( to_entries[] | .key as $j | .value
          | if   type != "string" then
              "\($loc) の description[\($j)] は文字列である必要があります"
            elif (test("^[^:]+:[^:]+:[^:]+$") | not) then
              "\($loc) の description[\($j)] は「入力:処理:出力」を半角コロンで結んだ3要素にしてください: \(.)"
            else empty
            end )
      else
        "\($loc) の description は文字列または文字列配列である必要があります"
      end;
    . as $root
    | [
        # 必須トップレベルキー
        ( ["top","layer","repository","dto"][] as $k
          | select( ($root | has($k)) | not )
          | "必須キーがありません: \($k)" ),

        # layer のキーは layer<数値>
        ( ($root.layer // {})
          | select(type == "object")
          | keys_unsorted[]
          | select( test("^layer[0-9]+$") | not )
          | "layer のキー名が不正です（layer<数値> であること）: \(.)" ),

        # top エンドポイントの必須フィールド
        ( ($root.top // [])
          | to_entries[]
          | .key as $i | .value as $e
          | ["path","method","description","imports"][] as $f
          | select( ($e | has($f)) | not )
          | "top[\($i)] に必須フィールドがありません: \($f)" ),

        # description 形式（「入力:処理:出力」）: top
        ( ($root.top // [])
          | to_entries[]
          | .key as $i | .value
          | select( has("description") )
          | .description | descErrors("top[\($i)]") ),

        # description 形式（「入力:処理:出力」）: layer
        ( ($root.layer // {})
          | select(type == "object")
          | to_entries[]
          | .key as $lk | (.value // [])
          | to_entries[]
          | .key as $i | .value
          | select( has("description") )
          | .description | descErrors("layer.\($lk)[\($i)]") ),

        # description 形式（「入力:処理:出力」）: internal
        ( ($root.internal // {})
          | select(type == "object")
          | to_entries[]
          | .key as $lk | (.value // [])
          | to_entries[]
          | .key as $i | .value
          | select( has("description") )
          | .description | descErrors("internal.\($lk)[\($i)]") ),

        # description 形式（「入力:処理:出力」）: repository
        ( ($root.repository // [])
          | to_entries[]
          | .key as $i | .value
          | select( has("description") )
          | .description | descErrors("repository[\($i)]") ),

        # imports 必須（dto 以外）: layer
        ( ($root.layer // {})
          | select(type == "object")
          | to_entries[]
          | .key as $lk | (.value // [])
          | to_entries[]
          | "\($lk)[\(.key)]" as $loc | .value
          | select( has("imports") | not )
          | "layer.\($loc) に imports がありません" ),

        # imports 必須（dto 以外）: repository
        ( ($root.repository // [])
          | to_entries[]
          | .key as $i | .value
          | select( has("imports") | not )
          | "repository[\($i)] に imports がありません" ),

        # repository.external の形式
        #   - 文字列（cognito 等のその他連携）
        #   - { "db":  { "schema": ... } }
        #   - { "api": { "url": ..., "method": ... } }
        ( ($root.repository // [])
          | to_entries[]
          | .key as $i | .value
          | select( has("external") )
          | .external as $ext
          | if   ($ext | type) == "string" then empty
            elif ($ext | type) == "object" then
              ( $ext | to_entries[]
                | .key as $ek | .value as $ev
                | if   $ek == "db"  then
                    ( ["schema"][] as $f
                      | select( ($ev | has($f)) | not )
                      | "repository[\($i)].external.db に \($f) がありません" )
                  elif $ek == "api" then
                    ( ["url","method"][] as $f
                      | select( ($ev | has($f)) | not )
                      | "repository[\($i)].external.api に \($f) がありません" )
                  else
                    "repository[\($i)].external のキーが不正です（db / api のみ）: \($ek)"
                  end )
            else
              "repository[\($i)].external の型が不正です（文字列 または オブジェクト）"
            end )
      ]
    | .[]
  ' "$file")"

  if [[ -n "$errors" ]]; then
    while IFS= read -r line; do
      error "$line"
    done <<< "$errors"
    exit 1
  fi

  info "レイヤー構成 OK: $file"
}

# ---------------------------------------------------------------------------
# レイヤールール検証（../java-builder/docs 由来）
#   このJSONから構造的に判定できるルールのみを実装する。
#   （文字列ハードコード/if-for禁止/サイズ制限等のASTレベルのルールは
#     Javaソースが必要なため対象外）
#
#   - projectName 形式（^[0-9A-Za-z_-]+$）           [directory-structure]
#   - layer 連番（1始まり・歯抜けなし）              [directory-structure]
#   - dto.in と dto.out の件数一致                    [directory-structure]
#   - 命名サフィックス(Repository/InDto/OutDto)        [code-rule D5]
#   - repository/<Stem>Repository ↔
#       dto/in/<Stem>InDto・dto/out/<Stem>OutDto 対応  [code-rule 4]
#   - import 宛先の実在（dangling 参照検出）          [整合性]
#   - layer1 は repository を import 必須             [code-rule 3]
#   - layer<N>(N≥2) は下位レイヤーを import 必須      [code-rule 7]
#   - レイヤー差 ≥2 の import 禁止＝降格              [layer-rule 14]
#   - 同一レイヤーの依存包含＝昇格／依存集合一致      [layer-rule 12]
#     （※ layer-rule 11 飛び越し参照は strict な 14 に内包されるため別途実装しない）
#   - top は最大レイヤーのみ import 可                [code-rule 8]
#   - external は repository のみ                     [layer-rule 15]
#   - internal の推移閉包に repository が現れない     [code-rule 10]
# ---------------------------------------------------------------------------
verify_rules() {
  local file="$1"
  local errors

  errors="$(jq -r '
    . as $root
    | ( ($root.layer // {}) | keys
        | map(capture("^layer(?<n>[0-9]+)$").n | tonumber) ) as $layernums
    | ( ($layernums | max) // 0 ) as $maxlayer

    # import 参照文字列 "<zone>/<Name>" を解決できるか
    | def resolves:
        . as $ref
        | if   test("^layer[0-9]+/")
          then capture("^layer(?<n>[0-9]+)/(?<name>.+)$") as $m
               | ( ($root.layer["layer"+$m.n] // []) | any(.name == $m.name) )
          elif test("^repository/")
          then sub("^repository/";"") as $name
               | ( ($root.repository // []) | any(.name == $name) )
          elif test("^internal/")
          then sub("^internal/";"") as $name
               | ( ($root.internal // {}) | has($name) )
          elif test("^dto/in/")
          then sub("^dto/in/";"") as $name | ( ($root.dto.in  // []) | any(. == $name) )
          elif test("^dto/out/")
          then sub("^dto/out/";"") as $name | ( ($root.dto.out // []) | any(. == $name) )
          elif test("^(constants|validation|util|log)/")
          then capture("^(?<z>constants|validation|util|log)/(?<name>.+)$") as $m
               | ( ($root[$m.z] // []) | any(. == $m.name) )
          else false
          end;

    # 参照文字列が指すコンポーネントの imports（グラフの出辺）
    def importsOf:
        . as $ref
        | if   test("^layer[0-9]+/")
          then capture("^layer(?<n>[0-9]+)/(?<name>.+)$") as $m
               | [ ($root.layer["layer"+$m.n] // [])[] | select(.name==$m.name) | (.imports // [])[] ]
          elif test("^internal/")
          then sub("^internal/";"") as $name
               | [ ($root.internal[$name] // [])[] | (.imports // [])[] ]
          elif test("^repository/")
          then sub("^repository/";"") as $name
               | [ ($root.repository // [])[] | select(.name==$name) | (.imports // [])[] ]
          else []
          end;

    [
        # projectName 形式
        ( $root | select(has("projectName")) | .projectName
          | select( test("^[0-9A-Za-z_-]+$") | not )
          | "projectName が不正です（^[0-9A-Za-z_-]+$）: \(.)" ),

        # layer 連番（1始まり・歯抜けなし）
        ( ($layernums | sort) as $ns
          | [range(1; ($ns|length)+1)] as $exp
          | select($ns != $exp)
          | "layer 番号は1始まりの連番である必要があります: 検出=[\($ns|join(","))] 期待=[\($exp|join(","))]" ),

        # dto.in / dto.out 件数一致
        ( select( (($root.dto.in // [])|length) != (($root.dto.out // [])|length) )
          | "dto.in と dto.out の件数が一致しません: in=\(($root.dto.in//[])|length) out=\(($root.dto.out//[])|length)" ),

        # 命名サフィックス（code-rule D5）
        ( ($root.repository // [])[] | .name as $rn
          | select( ($rn | endswith("Repository") and (length > 10)) | not )
          | "repository \"\($rn)\" は命名サフィックス \"Repository\" で終わる必要があります [code-rule D5]" ),
        ( ($root.dto.in // [])[] as $dn
          | select( ($dn | endswith("InDto") and (length > 5)) | not )
          | "dto/in \"\($dn)\" は命名サフィックス \"InDto\" で終わる必要があります [code-rule D5]" ),
        ( ($root.dto.out // [])[] as $dn
          | select( ($dn | endswith("OutDto") and (length > 6)) | not )
          | "dto/out \"\($dn)\" は命名サフィックス \"OutDto\" で終わる必要があります [code-rule D5]" ),

        # repository ↔ dto 対応（ステム一致, code-rule 4）
        # ステム = repository 名から末尾 "Repository" を除いた部分。<ステム>InDto / <ステム>OutDto が存在すること。
        ( ($root.repository // [])[] | .name as $rn
          | ($rn | rtrimstr("Repository")) as $stem
          | select( (($root.dto.in // [])|any(.== ($stem + "InDto"))) | not )
          | "repository \"\($rn)\" に対応する dto/in/\($stem + "InDto") がありません [code-rule 4]" ),
        ( ($root.repository // [])[] | .name as $rn
          | ($rn | rtrimstr("Repository")) as $stem
          | select( (($root.dto.out // [])|any(.== ($stem + "OutDto"))) | not )
          | "repository \"\($rn)\" に対応する dto/out/\($stem + "OutDto") がありません [code-rule 4]" ),

        # repository.imports は dto/in・dto/out をそれぞれ1件ずつ含む（code-rule 4）
        ( ($root.repository // []) | to_entries[] | .key as $i | .value as $r
          | ([ ($r.imports // [])[] | select(test("^dto/in/")) ] | length) as $nin
          | select($nin != 1)
          | "repository[\($i)] (\($r.name)) の imports に dto/in が \($nin) 件あります（ちょうど1件である必要）[code-rule 4]" ),
        ( ($root.repository // []) | to_entries[] | .key as $i | .value as $r
          | ([ ($r.imports // [])[] | select(test("^dto/out/")) ] | length) as $nout
          | select($nout != 1)
          | "repository[\($i)] (\($r.name)) の imports に dto/out が \($nout) 件あります（ちょうど1件である必要）[code-rule 4]" ),

        # repository.imports の dto/in・dto/out は対応ステム名と一致（code-rule 4）
        ( ($root.repository // []) | to_entries[] | .key as $i | .value as $r
          | ($r.name | rtrimstr("Repository")) as $stem
          | [ ($r.imports // [])[] | select(test("^dto/in/")) ] as $ins
          | select( ($ins | length) == 1 and ($ins[0] != ("dto/in/" + $stem + "InDto")) )
          | "repository[\($i)] (\($r.name)) の dto/in import が対応ステムと一致しません: \($ins[0])（期待: dto/in/\($stem + "InDto")）[code-rule 4]" ),
        ( ($root.repository // []) | to_entries[] | .key as $i | .value as $r
          | ($r.name | rtrimstr("Repository")) as $stem
          | [ ($r.imports // [])[] | select(test("^dto/out/")) ] as $outs
          | select( ($outs | length) == 1 and ($outs[0] != ("dto/out/" + $stem + "OutDto")) )
          | "repository[\($i)] (\($r.name)) の dto/out import が対応ステムと一致しません: \($outs[0])（期待: dto/out/\($stem + "OutDto")）[code-rule 4]" ),

        # import 宛先の実在（dangling 参照）
        ( ($root.top // []) | to_entries[] | .key as $i | (.value.imports // [])[] as $ref
          | select( ($ref|resolves) | not )
          | "top[\($i)] の import 先が存在しません: \($ref)" ),
        ( ($root.layer // {}) | to_entries[] | .key as $lk | (.value//[]) | to_entries[]
          | .key as $i | (.value.imports // [])[] as $ref
          | select( ($ref|resolves) | not )
          | "layer.\($lk)[\($i)] の import 先が存在しません: \($ref)" ),
        ( ($root.internal // {}) | to_entries[] | .key as $lk | (.value//[]) | to_entries[]
          | .key as $i | (.value.imports // [])[] as $ref
          | select( ($ref|resolves) | not )
          | "internal.\($lk)[\($i)] の import 先が存在しません: \($ref)" ),
        ( ($root.repository // []) | to_entries[] | .key as $i | (.value.imports // [])[] as $ref
          | select( ($ref|resolves) | not )
          | "repository[\($i)] の import 先が存在しません: \($ref)" ),

        # layer1 は repository を import 必須（code-rule 3）
        ( ($root.layer.layer1 // []) | to_entries[] | .key as $i | .value
          | select( ((.imports // []) | any(test("^repository/"))) | not )
          | "layer1[\($i)] (\(.name)) は repository を import していません [code-rule 3]" ),

        # layer<N>(N≥2) は下位レイヤーを import 必須（code-rule 7）
        ( ($root.layer // {}) | to_entries[]
          | (.key | capture("^layer(?<n>[0-9]+)$").n | tonumber) as $N
          | select($N >= 2)
          | .value | to_entries[] | .key as $i | .value
          | ([ (.imports // [])[] | select(test("^layer[0-9]+/")) | capture("^layer(?<n>[0-9]+)/").n | tonumber ]) as $lns
          | select( ($lns | any(. < $N)) | not )
          | "layer\($N)[\($i)] (\(.name)) は下位レイヤー(layer1..layer\($N-1))を import していません [code-rule 7]" ),

        # レイヤー差 ≥2 の import 禁止＝降格（layer-rule 14）
        ( ($root.layer // {}) | to_entries[]
          | (.key | capture("^layer(?<n>[0-9]+)$").n | tonumber) as $N
          | .value | to_entries[] | .key as $i | .value as $c
          | ($c.imports // [])[] | select(test("^layer[0-9]+/"))
          | capture("^layer(?<n>[0-9]+)/(?<name>.+)$") as $m | ($m.n|tonumber) as $M
          | select( ($N - $M) >= 2 )
          | "layer\($N)[\($i)] (\($c.name)) が layer\($M)/\($m.name) を import（レイヤー差>=2は禁止。layer\($M+1) へ降格してください）[layer-rule 14]" ),

        # 同一レイヤーの依存包含＝昇格（layer-rule 12）
        #   同レイヤーの相異なる2コンポーネント a, b の「直接 import 集合」を比較する。
        #   - imports(b) ⊆ imports(a) かつ真包含（|A|>|B|・B 非空） → a を上位へ昇格し b を import せよ。
        #   - imports(a) == imports(b)（同一・非空） → 依存が完全重複。統合 or 責務分離（name 昇順で1回だけ報告）。
        ( ($root.layer // {}) | to_entries[]
          | .key as $lk | (.value // []) as $comps
          | range(0; ($comps|length)) as $i | range(0; ($comps|length)) as $j
          | select($i != $j)
          | ($comps[$i]) as $a | ($comps[$j]) as $b
          | ($a.imports // [] | unique) as $A
          | ($b.imports // [] | unique) as $B
          | select( ($B|length) > 0 and (($B - $A)|length) == 0 )
          | if   ($A|length) > ($B|length)
            then "依存包含: layer.\($lk) の \($a.name) は \($b.name) の依存集合を包含しています（\($a.name) を上位レイヤーへ昇格し \($b.name) を import してください）[layer-rule 12]"
            elif ($a.name < $b.name)
            then "依存集合一致: layer.\($lk) の \($a.name) と \($b.name) は直接 import 集合が同一です（統合するか責務を分離してください）[layer-rule 12]"
            else empty
            end ),

        # top は最大レイヤーのみ import 可（code-rule 8）
        ( ($root.top // []) | to_entries[] | .key as $i | .value as $e
          | ($e.imports // [])[] | select(test("^layer[0-9]+/"))
          | capture("^layer(?<n>[0-9]+)/(?<name>.+)$") as $m | ($m.n|tonumber) as $M
          | select($M != $maxlayer)
          | "top[\($i)] (\($e.operationId // "?")) は最大レイヤー(layer\($maxlayer))以外を import しています: layer\($M)/\($m.name) [code-rule 8]" ),

        # external は repository のみ（layer-rule 15）
        ( ($root.top // [])[] | select(has("external"))
          | "top のエンドポイントに external は持てません（repository のみ）[layer-rule 15]" ),
        ( ($root.layer // {}) | to_entries[] | .key as $lk | (.value//[])[] | select(has("external"))
          | "layer.\($lk) のコンポーネントに external は持てません（repository のみ）[layer-rule 15]" ),
        ( ($root.internal // {}) | to_entries[] | .key as $lk | (.value//[])[] | select(has("external"))
          | "internal.\($lk) のコンポーネントに external は持てません（repository のみ）[layer-rule 15]" ),

        # internal の推移閉包に repository が現れてはならない（code-rule 10）
        ( ( ($root.internal // {}) | keys | map("internal/" + .) ) as $seed
          | ( $seed
              | until( ([ .[] | importsOf[] ] - . | length) == 0;
                       (. + [ .[] | importsOf[] ]) | unique ) ) as $closure
          | $closure[] | select(test("^repository/"))
          | "internal が repository に到達しています（backend完結ではない）: \(.) [code-rule 10]" )
      ]
    | .[]
  ' "$file")"

  if [[ -n "$errors" ]]; then
    while IFS= read -r line; do
      error "$line"
    done <<< "$errors"
    exit 1
  fi

  info "レイヤールール OK: $file"
}

# ---------------------------------------------------------------------------
# メイン
# ---------------------------------------------------------------------------
main() {
  if [[ $# -lt 1 ]]; then
    error "使い方: $0 <input.json>"
    exit 1
  fi

  ensure_jq
  verify_json "$1"
  verify_structure "$1"
  verify_rules "$1"

  info "検証が完了しました。"
}

main "$@"
