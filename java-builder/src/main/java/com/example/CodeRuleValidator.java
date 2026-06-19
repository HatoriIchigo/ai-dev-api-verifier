package com.example;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * JavaParser の AST を用いてコード内容のルールを検証する。
 *
 * <ol>
 *   <li>文字列リテラルのハードコードは {@code constants/}・{@code validation/} 以外で禁止。</li>
 *   <li>（1.2）非決定的な乱数・ID 生成の禁止（全レイヤー）: {@code UUID.randomUUID()} /
 *       {@code Math.random()} / {@code new Random()}。受け取る／注入する（{@code SecureRandom} は対象外）。</li>
 *   <li>{@code dto/**}・{@code repository/*}・{@code constants/*} で条件・繰り返し処理を禁止。</li>
 *   <li>{@code layer1/} の各クラスは {@code repository} を import していること。</li>
 *   <li>{@code repository/Foo.java} には {@code dto/in/Foo.java} と {@code dto/out/Foo.java}
 *       （ベース名完全一致）が対応して存在すること。</li>
 *   <li>（4.1）{@code dto/**} の各クラスは Lombok アノテーション（{@code @Data} 等）を必須とする。</li>
 *   <li>固定値（リテラル）の return を禁止。{@code constants/}・{@code validation/} は対象外。
 *       （null の扱いは 5.1 に従う）</li>
 *   <li>（5.1）null の使用制限（全レイヤー）: 許可は {@code ==}/{@code !=} 比較と catch 内 {@code return null;} のみ。
 *       代入・初期化・引数・catch 外 return 等は禁止。</li>
 *   <li>1ファイル {@value #MAX_FILE_LINES} 行以内、1メソッド／コンストラクタ {@value #MAX_METHOD_LINES} 行以内。</li>
 *   <li>{@code layer<N>}（N≥2）は下位レイヤーのいずれかを import すること。</li>
 *   <li>{@code top/*.java} は最大レイヤーのみ import 可能。</li>
 *   <li>{@code util/*.java} は {@code layer*}・{@code top}・{@code repository} を import 不可。</li>
 * </ol>
 *
 * <p>さらに、レイヤー番号付きクラス間の import 依存グラフに対して
 * {@link #validateLayerDependencies(List)} で飛び越し参照・依存包含・依存重複・レイヤー差を検証する。
 */
public final class CodeRuleValidator {

    private static final Pattern LAYER_DIR = Pattern.compile("^layer([0-9]+)$");

    /**
     * SQL 文（{@code SELECT}/{@code INSERT} 等の DML/DDL キーワードで始まる文字列）を検出する。
     * 大文字小文字・改行を無視し、キーワード直後に空白が続くもののみを SQL とみなす
     * （"Selected" 等の通常文字列を誤検出しないため）。
     */
    private static final Pattern SQL_LITERAL = Pattern.compile(
            "(?is)^\\s*(SELECT|INSERT|UPDATE|DELETE|MERGE|CREATE|DROP|ALTER|TRUNCATE)\\s+.*");

    private static final int MAX_FILE_LINES = 500;
    private static final int MAX_METHOD_LINES = 100;

    /** ルールD5/4: ゾーン別の命名サフィックス。ステム一致のペアリングにも用いる。 */
    private static final String REPOSITORY_SUFFIX = "Repository";
    private static final String DTO_IN_SUFFIX = "InDto";
    private static final String DTO_OUT_SUFFIX = "OutDto";

    /**
     * DTO（{@code dto/**}）クラスに必須とする Lombok アノテーション（単純名）。
     * getter/setter・コンストラクタ等のボイラープレートは手書きせず Lombok に委ねる方針で、
     * 各 DTO クラスに少なくとも1つ付与されていること（推奨は {@code @Data} / {@code @Value}）。
     */
    private static final Set<String> DTO_LOMBOK_ANNOTATIONS = Set.of(
            "Data", "Value", "Getter", "Setter", "Builder", "SuperBuilder",
            "AllArgsConstructor", "NoArgsConstructor", "RequiredArgsConstructor",
            "EqualsAndHashCode", "ToString", "With", "Accessors");

    /** 外部設定ファイル名（カレントディレクトリ／JAR同梱リソースの両方で使用）。 */
    private static final String EXTERNAL_PACKAGES_FILE = "external-packages.txt";

    /** シークレット識別子キーワードの外部設定ファイル名。 */
    private static final String SECRET_KEYWORDS_FILE = "secret-keywords.txt";

    /**
     * 値そのものが外部化すべき形式（確実なものに限定）を検出するパターン。
     * 名前が紛らわしくない場合の取りこぼしを補完する（シークレット・接続URL・URL等）。
     */
    private static final List<Pattern> SECRET_VALUE_PATTERNS = List.of(
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"), // PEM 秘密鍵
            Pattern.compile("\\b(AKIA|ASIA)[0-9A-Z]{16}\\b"),       // AWS アクセスキーID
            Pattern.compile("\\bghp_[0-9A-Za-z]{36}\\b"),           // GitHub Personal Access Token
            Pattern.compile("\\bxox[baprs]-[0-9A-Za-z-]{10,}"),     // Slack トークン
            Pattern.compile("(?i)^jdbc:"),                          // JDBC 接続URL
            Pattern.compile("(?i)^[a-z][a-z0-9+.-]*://"));          // URL（http(s)/ftp/ws 等の scheme://）

    private final Path appDir;
    private final String basePackage;
    private final String repositoryPackage;
    private final List<String> externalPackages;
    private final List<String> secretKeywords;

    public CodeRuleValidator(Path appDir, String basePackage) {
        this.appDir = appDir;
        this.basePackage = basePackage;
        this.repositoryPackage = basePackage + ".repository";
        this.externalPackages = loadExternalPackages();
        this.secretKeywords = loadSecretKeywords();
    }

    public List<String> validate() throws IOException {
        List<String> violations = new ArrayList<>();

        List<Path> javaFiles;
        try (Stream<Path> stream = Files.walk(appDir)) {
            javaFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }

        // ルール4: repository <-> dto/in, dto/out のベース名一致（ファイル存在ベース）
        violations.addAll(validateRepositoryDtoPairs(javaFiles));

        // 存在するサービス層の最大番号（top のimport制限に使用）
        OptionalInt maxLayer = javaFiles.stream()
                .map(this::parentDir)
                .map(LAYER_DIR::matcher)
                .filter(Matcher::matches)
                .mapToInt(m -> Integer.parseInt(m.group(1)))
                .max();

        ParserConfiguration config = new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_21);
        // クロスクラスの参照解決（値フロー検査で使用）。ソースルートが取得できない場合は
        // シンボル解決なしで動作する（resolve() が失敗し、値フロー検査が無効化されるだけ）。
        Path sourceRoot = sourceRootOf(appDir);
        if (sourceRoot != null && Files.isDirectory(sourceRoot)) {
            // 型ソルバが他ファイルを再パースする際も Java 21 構文を扱えるようにする
            ParserConfiguration solverConfig =
                    new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_21);
            CombinedTypeSolver typeSolver = new CombinedTypeSolver(new ReflectionTypeSolver());
            typeSolver.add(new JavaParserTypeSolver(sourceRoot, solverConfig));
            config.setSymbolResolver(new JavaSymbolSolver(typeSolver));
        }
        JavaParser parser = new JavaParser(config);

        List<Source> sources = new ArrayList<>();

        for (Path file : javaFiles) {
            String dir = parentDir(file);

            ParseResult<CompilationUnit> result = parser.parse(file);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                violations.add(rel(file) + " Javaの構文解析に失敗しました");
                continue;
            }
            CompilationUnit cu = result.getResult().get();
            sources.add(new Source(file, dir, cu));

            // ルール1.5: ワイルドカード import の禁止（全レイヤー対象）
            violations.addAll(validateNoWildcardImports(file, cu));

            // サイズ制限: ファイル行数・メソッド/コンストラクタ行数
            int fileLines = Files.readAllLines(file).size();
            if (fileLines > MAX_FILE_LINES) {
                violations.add(rel(file) + " ファイルが " + MAX_FILE_LINES + " 行を超えています: " + fileLines + "行");
            }
            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                int lines = span(method);
                if (lines > MAX_METHOD_LINES) {
                    violations.add(loc(file, method) + " メソッド " + method.getNameAsString()
                            + " が " + MAX_METHOD_LINES + " 行を超えています: " + lines + "行");
                }
            }
            for (ConstructorDeclaration ctor : cu.findAll(ConstructorDeclaration.class)) {
                int lines = span(ctor);
                if (lines > MAX_METHOD_LINES) {
                    violations.add(loc(file, ctor) + " コンストラクタ " + ctor.getNameAsString()
                            + " が " + MAX_METHOD_LINES + " 行を超えています: " + lines + "行");
                }
            }

            boolean hardcodeExempt = dir.equals("constants") || dir.equals("validation");

            // ルール1: 文字列リテラルのハードコード禁止 / SQL文字列・シークレット値の禁止
            for (StringLiteralExpr literal : cu.findAll(StringLiteralExpr.class)) {
                String value = literal.getValue();
                if (isSqlLiteral(value)) {
                    // SQL（SELECT/INSERT 等）は constants/ や validation/ でも例外なく禁止。
                    // 生SQLの集約ではなく SQL mapper（MyBatis / JPA 等）の利用を優先する。
                    violations.add(loc(file, literal)
                            + " SQL文字列のハードコードは禁止です。SQL mapper（MyBatis / JPA 等）の仕様を優先してください: \""
                            + preview(value) + "\"");
                } else if (looksLikeSecretValue(value)) {
                    // 値の形式が外部化対象（秘密鍵・APIキー・接続URL・URL等）。例外なく禁止し yaml+env へ誘導。
                    violations.add(loc(file, literal)
                            + " 外部化すべき値（シークレット・接続情報・URL 等）のハードコードは禁止です。application.yaml + 環境変数経由で注入してください: \""
                            + preview(value) + "\"");
                } else if (!hardcodeExempt) {
                    violations.add(loc(file, literal)
                            + " 文字列リテラルのハードコードは禁止です（constants/ か validation/ に集約）: \""
                            + preview(value) + "\"");
                }
            }

            // 外部化対象の混入防止（名前ベース）: constants/validation（文字列リテラルが許可される場所）
            // でのみ検査する。他の場所は文字列リテラル自体がルール1で禁止のため重複報告しない。
            // 定数名が外部化キーワード（シークレット・ユーザ名・DB・URL 系）に該当し、文字列リテラルで
            // 初期化されている場合は禁止。
            if (hardcodeExempt) {
                for (VariableDeclarator var : cu.findAll(VariableDeclarator.class)) {
                    boolean initByString = var.getInitializer()
                            .map(init -> !init.findAll(StringLiteralExpr.class).isEmpty())
                            .orElse(false);
                    if (initByString && matchesSecretKeyword(var.getNameAsString())) {
                        violations.add(loc(file, var)
                                + " 外部化すべき値（シークレット・ユーザ名・DB接続情報・URL 等）のハードコードは禁止です（"
                                + var.getNameAsString() + "）。application.yaml + 環境変数経由で注入してください");
                    }
                }
            }

            // ルール5.1: null の使用制限（全レイヤー対象。== / != 比較と catch 内 return null のみ許可）
            violations.addAll(validateNullUsage(file, cu));

            // ルール5: 固定値（リテラル）の return 禁止（null は別途ルール5.1で制限）
            if (!hardcodeExempt) {
                for (ReturnStmt ret : cu.findAll(ReturnStmt.class)) {
                    Expression expr = ret.getExpression().orElse(null);
                    if (expr != null && isFixedLiteral(expr)) {
                        violations.add(loc(file, ret)
                                + " 固定値（リテラル）のreturnは禁止です: return " + expr + ";");
                    }
                }
            }

            // ルール2: 条件・繰り返し処理の禁止
            if (isControlFlowForbidden(dir)) {
                flag(cu, IfStmt.class, "if文", file, dir, violations);
                flag(cu, ForStmt.class, "for文", file, dir, violations);
                flag(cu, ForEachStmt.class, "拡張for文", file, dir, violations);
                flag(cu, WhileStmt.class, "while文", file, dir, violations);
                flag(cu, DoStmt.class, "do-while文", file, dir, violations);
                flag(cu, SwitchStmt.class, "switch文", file, dir, violations);
                flag(cu, SwitchExpr.class, "switch式", file, dir, violations);
                flag(cu, ConditionalExpr.class, "三項演算子", file, dir, violations);
            }

            // ルール1.2: 非決定的な乱数・ID 生成の禁止（UUID.randomUUID / Math.random / new Random、全レイヤー対象）
            violations.addAll(validateNoNondeterministicRandom(file, cu));

            // ルール4.1: DTO は Lombok の利用を必須とする
            if (isDtoDir(dir)) {
                violations.addAll(validateDtoLombok(file, cu));
            }

            // ルール1.3: constants の定数定義制約（DTO import 禁止 / public static final / String・プリミティブ / リテラル初期化）
            if (dir.equals("constants")) {
                violations.addAll(validateConstantsDefinitions(file, cu));
            }

            // ルール4.2/4.3: repository はインメモリ実装を禁止（外部連携 import 必須 / コレクション state 禁止）
            if (dir.equals("repository")) {
                violations.addAll(validateRepositoryNotInMemory(file, cu));
                // ルール4（imports）: 対応する dto/in・dto/out を各1件 import すること
                violations.addAll(validateRepositoryDtoImports(file, cu));
            }

            // 外部ツール連携の import は repository/ でのみ許可
            if (!dir.equals("repository")) {
                for (ImportDeclaration imp : cu.getImports()) {
                    String name = imp.getNameAsString();
                    if (isExternalImport(name)) {
                        violations.add(loc(file, imp)
                                + " 外部ツール連携の import は repository/ でのみ許可されます: " + name);
                    }
                }
            }

            // util/*.java は layer*/top/repository を import 不可
            if (dir.equals("util")) {
                for (ImportDeclaration imp : cu.getImports()) {
                    String name = imp.getNameAsString();
                    String forbidden = utilForbiddenImport(name);
                    if (forbidden != null) {
                        violations.add(loc(file, imp)
                                + " util は " + forbidden + " を import できません: " + name);
                    }
                }
            }

            // レイヤー依存ルール
            Matcher layer = LAYER_DIR.matcher(dir);
            if (layer.matches()) {
                int n = Integer.parseInt(layer.group(1));
                if (n == 1) {
                    // ルール3: layer1 の各クラスは repository を利用すること
                    if (!usesRepository(cu)) {
                        violations.add(rel(file) + " layer1 のクラスは repository を利用する必要があります（"
                                + repositoryPackage + " の import が見つかりません）");
                    }
                } else if (!importsLowerLayer(cu, n)) {
                    // layer2以降は下位レイヤーのいずれかを import すること
                    violations.add(rel(file) + " layer" + n + " は下位レイヤー（layer1〜layer" + (n - 1)
                            + "）のいずれかを import する必要があります");
                }

                // ルール7.1: データソース到達の呼び出しレベル検査（DTO を返す公開メソッドは
                // repository/下位レイヤーに到達せず return してはならない）
                violations.addAll(validateLayerReachesDataSource(file, cu, n));
            }

            // top/*.java は最大レイヤーのみ import 可能
            if (dir.equals("top") && maxLayer.isPresent()) {
                int max = maxLayer.getAsInt();
                for (ImportDeclaration imp : cu.getImports()) {
                    OptionalInt imported = layerOfImport(imp.getNameAsString());
                    if (imported.isPresent() && imported.getAsInt() != max) {
                        violations.add(loc(file, imp) + " top は最大レイヤー（layer" + max
                                + "）のみ import 可能です: layer" + imported.getAsInt() + " を import しています");
                    }
                }
            }
        }

        // レイヤー間のクラス依存ルール（飛び越し・包含・重複・レイヤー差）
        violations.addAll(validateLayerDependencies(sources));

        // ルール10: internal は repository／外部連携へ推移的に到達してはならない
        violations.addAll(validateInternalReach(sources));

        // ルール16: 外部連携呼び出しに渡る値が constants/ 由来（直書き）でないこと（値フロー検査）
        violations.addAll(validateTaintToExternal(sources));

        return violations;
    }

    private record Source(Path file, String dir, CompilationUnit cu) { }

    /**
     * レイヤー番号付きクラス間の import 依存グラフを構築し、レイヤールールを検証する。
     *
     * <ul>
     *   <li>飛び越し参照: {@code C->B} で間のレイヤーの {@code A} が {@code B} を import 済みなら禁止。</li>
     *   <li>依存包含: 同レイヤーの一方の依存集合が他方を包含 → 包含側を昇格。</li>
     *   <li>依存重複: 同レイヤーで依存集合が部分的に重複 → 共通分を別クラスへ切り出し。</li>
     *   <li>レイヤー差: {@code X->Y} で差が2以上 → {@code X} を降格。</li>
     * </ul>
     */
    private List<String> validateLayerDependencies(List<Source> sources) {
        // レイヤークラスの登録（FQN -> レイヤー番号 / ファイル）
        Map<String, Integer> layerByFqn = new TreeMap<>();
        Map<String, Path> fileByFqn = new HashMap<>();
        for (Source s : sources) {
            Matcher m = LAYER_DIR.matcher(s.dir());
            if (m.matches()) {
                String fqn = fqnOf(s.file(), s.dir());
                layerByFqn.put(fqn, Integer.parseInt(m.group(1)));
                fileByFqn.put(fqn, s.file());
            }
        }

        // 依存集合（FQN -> import している同プロジェクトのレイヤークラス FQN 群）
        Map<String, Set<String>> deps = new TreeMap<>();
        for (Source s : sources) {
            if (LAYER_DIR.matcher(s.dir()).matches()) {
                String fqn = fqnOf(s.file(), s.dir());
                deps.put(fqn, layerDeps(s.cu(), fqn, layerByFqn));
            }
        }

        List<String> violations = new ArrayList<>();

        for (Map.Entry<String, Set<String>> entry : deps.entrySet()) {
            String consumer = entry.getKey();
            int lc = layerByFqn.get(consumer);
            for (String target : entry.getValue()) {
                int lt = layerByFqn.get(target);

                // ルールD: レイヤー差が2以上の依存は降格
                if (lc - lt >= 2) {
                    violations.add(rel(fileByFqn.get(consumer)) + " レイヤー差が2以上の依存: "
                            + simpleName(consumer) + "(layer" + lc + ") -> " + simpleName(target)
                            + "(layer" + lt + ")。" + simpleName(consumer) + " を layer" + (lt + 1)
                            + " に降格してください（基準は下位レイヤー）");
                }

                // ルールA: 間のレイヤーに target を import するクラスがあれば飛び越し禁止
                if (lt < lc) {
                    for (Map.Entry<String, Set<String>> mid : deps.entrySet()) {
                        String intermediate = mid.getKey();
                        if (intermediate.equals(consumer) || intermediate.equals(target)) {
                            continue;
                        }
                        int li = layerByFqn.get(intermediate);
                        if (li > lt && li < lc && mid.getValue().contains(target)) {
                            violations.add(rel(fileByFqn.get(consumer)) + " レイヤーの飛び越し参照: "
                                    + simpleName(consumer) + "(layer" + lc + ") は " + simpleName(target)
                                    + "(layer" + lt + ") を直接 import できません。間の " + simpleName(intermediate)
                                    + "(layer" + li + ") が import 済みのため、" + simpleName(intermediate)
                                    + " を経由してください");
                            break;
                        }
                    }
                }
            }
        }

        // ルールB/C: 同一レイヤー内で依存集合を共有するクラス対
        Map<Integer, List<String>> byLayer = new TreeMap<>();
        for (Map.Entry<String, Integer> e : layerByFqn.entrySet()) {
            byLayer.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        for (Map.Entry<Integer, List<String>> e : byLayer.entrySet()) {
            int layer = e.getKey();
            List<String> classes = e.getValue();
            classes.sort(null);
            for (int i = 0; i < classes.size(); i++) {
                for (int j = i + 1; j < classes.size(); j++) {
                    String x = classes.get(i);
                    String y = classes.get(j);
                    Set<String> dx = deps.getOrDefault(x, Set.of());
                    Set<String> dy = deps.getOrDefault(y, Set.of());

                    Set<String> common = new TreeSet<>(dx);
                    common.retainAll(dy);
                    if (common.isEmpty()) {
                        continue;
                    }

                    boolean xSubsetY = dy.containsAll(dx);
                    boolean ySubsetX = dx.containsAll(dy);
                    if (xSubsetY || ySubsetX) {
                        // ルールB: 一方が他方を包含 → 包含側(superset)を昇格し被包含側を利用
                        String subset = xSubsetY ? x : y;
                        String superset = xSubsetY ? y : x;
                        violations.add(rel(fileByFqn.get(superset)) + " 同一レイヤーの依存包含: "
                                + simpleName(superset) + "(layer" + layer + ") の依存は同レイヤー "
                                + simpleName(subset) + " の依存 {" + simpleNames(deps.get(subset))
                                + "} を包含しています。" + simpleName(subset) + " を import して利用し、"
                                + simpleName(superset) + " を昇格してください");
                    } else {
                        // ルールC: 部分的に重複 → 共通依存を別クラスへ切り出し
                        violations.add(rel(fileByFqn.get(x)) + " 同一レイヤーの依存重複: "
                                + simpleName(x) + " と " + simpleName(y) + "(layer" + layer
                                + ") が依存 {" + simpleNames(common)
                                + "} を共有しています。共通依存を制御する新クラス(layer" + layer
                                + ")へ切り出し、両者を昇格して直接 import を禁止してください");
                    }
                }
            }
        }

        return violations;
    }

    /** import を同プロジェクトのレイヤークラス FQN 集合に解決する。 */
    private Set<String> layerDeps(CompilationUnit cu, String selfFqn, Map<String, Integer> layerByFqn) {
        return projectImports(cu, selfFqn, layerByFqn.keySet());
    }

    /**
     * import 文を、{@code known} に含まれる同プロジェクトクラスの FQN 集合に解決する。
     * ワイルドカード import はパッケージ一致するクラスをすべて展開する。
     */
    private Set<String> projectImports(CompilationUnit cu, String selfFqn, Set<String> known) {
        Set<String> result = new TreeSet<>();
        for (ImportDeclaration imp : cu.getImports()) {
            String name = imp.getNameAsString();
            if (imp.isAsterisk()) {
                for (String fqn : known) {
                    if (!fqn.equals(selfFqn) && packageOf(fqn).equals(name)) {
                        result.add(fqn);
                    }
                }
            } else if (!name.equals(selfFqn) && known.contains(name)) {
                result.add(name);
            }
        }
        return result;
    }

    /**
     * ルール10: {@code internal/*.java} の各エントリから import を推移的に辿り、到達閉包に
     * {@code repository} パッケージのクラス、または外部連携パッケージを import するクラスが
     * 含まれていればエラーとする（internal は backend 完結で外部接続なしであることの保証）。
     */
    private List<String> validateInternalReach(List<Source> sources) {
        // 全 app クラスの FQN -> Source
        Map<String, Source> byFqn = new TreeMap<>();
        for (Source s : sources) {
            byFqn.put(fqnGeneral(s.file(), s.dir()), s);
        }

        // 各クラスの直接依存（プロジェクト内クラス）と、外部連携 import の有無
        Map<String, Set<String>> deps = new HashMap<>();
        Map<String, Boolean> importsExternal = new HashMap<>();
        for (Map.Entry<String, Source> e : byFqn.entrySet()) {
            deps.put(e.getKey(), projectImports(e.getValue().cu(), e.getKey(), byFqn.keySet()));
            importsExternal.put(e.getKey(), hasExternalImport(e.getValue().cu()));
        }

        List<String> violations = new ArrayList<>();
        for (Source s : sources) {
            if (!s.dir().equals("internal")) {
                continue;
            }
            String entry = fqnGeneral(s.file(), s.dir());

            Set<String> visited = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(entry);
            Set<String> reachedRepo = new TreeSet<>();
            Set<String> reachedExternal = new TreeSet<>();
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                if (!visited.add(cur)) {
                    continue;
                }
                Source cs = byFqn.get(cur);
                if (cs != null && !cur.equals(entry) && cs.dir().equals("repository")) {
                    reachedRepo.add(cur);
                }
                if (Boolean.TRUE.equals(importsExternal.get(cur))) {
                    reachedExternal.add(cur);
                }
                queue.addAll(deps.getOrDefault(cur, Set.of()));
            }

            if (!reachedRepo.isEmpty() || !reachedExternal.isEmpty()) {
                StringBuilder sb = new StringBuilder(rel(s.file())
                        + " internal は backend 完結のため repository／外部連携へ到達してはいけません");
                if (!reachedRepo.isEmpty()) {
                    sb.append("（repository 到達: ").append(simpleNames(reachedRepo)).append("）");
                }
                if (!reachedExternal.isEmpty()) {
                    sb.append("（外部連携 import 到達: ").append(simpleNames(reachedExternal)).append("）");
                }
                violations.add(sb.toString());
            }
        }
        return violations;
    }

    /**
     * ルール16（値フロー検査）: {@code repository/} の外部クライアント呼び出し／生成に渡る引数が、
     * {@code constants/} 由来の値（直書きの定数。別名ローカル変数・フィールド経由を含む）であれば
     * エラーとする。接続情報・シークレットは {@code constants/} ではなく {@code application.yaml} +
     * 環境変数（{@code @Value} / {@code System.getenv}）経由で注入させるため。
     *
     * <p>クロスクラス解決には JavaParser の {@link JavaSymbolSolver} を用いる。シンボル解決が
     * 構成できない場合（ソースルート未取得・外部jar未解決など）は、その引数の判定をスキップする
     * （誤検知を出さない方針）。
     *
     * <p><b>v1 の追跡範囲</b>: 「constants の定数 → repository での使用（直接／同一クラス内の別名）」を
     * 追跡する。レイヤーをまたいでメソッド引数として渡ってきた値（呼び出し元での実引数）までは辿らない。
     * また、変数に束ねず連鎖呼び出ししている外部クライアント（{@code X.builder().build().call(..)}）は
     * レシーバ型を解決できないため検出対象外。
     */
    private List<String> validateTaintToExternal(List<Source> sources) {
        // constants/ クラスの FQN 集合（汚染源の判定に使用）
        Set<String> constantFqns = new HashSet<>();
        for (Source s : sources) {
            if (s.dir().equals("constants")) {
                constantFqns.add(fqnOf(s.file(), "constants"));
            }
        }
        if (constantFqns.isEmpty()) {
            return List.of();
        }

        // 重複報告（FieldAccessExpr とその内側 NameExpr 等）を避けるため Set で集約
        Set<String> violations = new LinkedHashSet<>();
        for (Source s : sources) {
            if (!s.dir().equals("repository")) {
                continue;
            }
            Set<String> externalTypes = externalSimpleTypeNames(s.cu());
            if (externalTypes.isEmpty()) {
                continue;
            }
            Map<String, String> declaredTypes = declaredTypeSimpleNames(s.cu());

            // 外部クライアントのメソッド呼び出し（receiver.method(args)）
            for (MethodCallExpr call : s.cu().findAll(MethodCallExpr.class)) {
                if (!isExternalSink(call, externalTypes, declaredTypes)) {
                    continue;
                }
                for (Expression arg : call.getArguments()) {
                    checkArgForConstants(arg, s.file(), constantFqns, violations);
                }
            }
            // 外部クライアントの生成（new ExternalType(args)）
            for (ObjectCreationExpr created : s.cu().findAll(ObjectCreationExpr.class)) {
                if (!externalTypes.contains(created.getType().getNameAsString())) {
                    continue;
                }
                for (Expression arg : created.getArguments()) {
                    checkArgForConstants(arg, s.file(), constantFqns, violations);
                }
            }
        }
        return new ArrayList<>(violations);
    }

    /**
     * ルール7.1（ルール3/7 の呼び出しレベル強化）: layer がデータソース（layer1=repository、
     * layerN(N&ge;2)=下位レイヤー）に到達せず DTO を返す（=データソースに降りる前に return する）のを禁止する。
     *
     * <p>対象は「戻り値型が {@code dto/**} で、クラス内の他メソッドから呼ばれていない public メソッド」
     * （=上位から呼ばれるエントリ）。これらは本体内、または同一クラス内の委譲チェーン経由で、データソースの
     * メソッド呼び出しに到達しなければならない。内部ヘルパ／純変換メソッド（クラス内から呼ばれているもの）は
     * 対象外として誤検知を抑える。
     *
     * <p>import レベルでデータソースが無い場合は、ルール3（layer1）／ルール7（layerN）が別途報告するため、
     * 本検査は何もしない（重複報告の回避）。クロスクラスの完全な制御フロー解析は行わない（既知の限界）。
     */
    private List<String> validateLayerReachesDataSource(Path file, CompilationUnit cu, int n) {
        Set<String> sourceTypes = layerSourceSimpleTypeNames(cu, n);
        if (sourceTypes.isEmpty()) {
            return List.of();
        }
        Set<String> dtoTypes = dtoSimpleTypeNames(cu);
        if (dtoTypes.isEmpty()) {
            return List.of();
        }
        Map<String, String> declaredTypes = declaredTypeSimpleNames(cu);

        List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getBody().isPresent())
                .toList();

        // 各メソッドの「直接到達」フラグと、同一クラス内で呼ぶメソッド名（委譲）を収集する。
        Map<MethodDeclaration, Boolean> directReaches = new HashMap<>();
        Map<MethodDeclaration, Set<String>> internalCalls = new HashMap<>();
        Set<String> calledInternally = new HashSet<>();
        for (MethodDeclaration m : methods) {
            boolean direct = false;
            Set<String> siblings = new HashSet<>();
            for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
                if (isExternalSink(call, sourceTypes, declaredTypes)) {
                    direct = true;
                }
                Optional<Expression> sc = call.getScope();
                if (sc.isEmpty() || sc.get().isThisExpr()) {
                    siblings.add(call.getNameAsString());
                    calledInternally.add(call.getNameAsString());
                }
            }
            directReaches.put(m, direct);
            internalCalls.put(m, siblings);
        }

        // メソッド名単位で到達可能性を不動点計算（同一クラス内委譲チェーンを辿る。overload は名前で合算）。
        Map<String, Boolean> reaches = new HashMap<>();
        for (MethodDeclaration m : methods) {
            boolean direct = Boolean.TRUE.equals(directReaches.get(m));
            reaches.merge(m.getNameAsString(), direct, (a, b) -> a || b);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (MethodDeclaration m : methods) {
                String name = m.getNameAsString();
                if (Boolean.TRUE.equals(reaches.get(name))) {
                    continue;
                }
                for (String callee : internalCalls.get(m)) {
                    if (Boolean.TRUE.equals(reaches.get(callee))) {
                        reaches.put(name, true);
                        changed = true;
                        break;
                    }
                }
            }
        }

        List<String> violations = new ArrayList<>();
        String sourceLabel = (n == 1) ? "repository" : "下位レイヤー";
        for (MethodDeclaration m : methods) {
            if (!m.isPublic() || !dtoTypes.contains(simpleTypeName(m.getType()))) {
                continue;
            }
            // 内部ヘルパ（クラス内の他メソッドから呼ばれている）は上位エントリではないため対象外。
            if (calledInternally.contains(m.getNameAsString())) {
                continue;
            }
            if (!Boolean.TRUE.equals(reaches.get(m.getNameAsString()))) {
                violations.add(loc(file, m) + " layer" + n + " の公開メソッド " + m.getNameAsString()
                        + "（戻り値 " + simpleTypeName(m.getType()) + "）が " + sourceLabel
                        + " を呼び出していません（データソースに到達せず DTO を返しています）");
            }
        }
        return violations;
    }

    /**
     * layer のデータソース型の単純名集合を返す。
     * layer1 は repository パッケージのクラス、layerN(N&ge;2) は自分より下位の layer のクラス（import 経由）。
     */
    private Set<String> layerSourceSimpleTypeNames(CompilationUnit cu, int n) {
        Set<String> names = new HashSet<>();
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isAsterisk()) {
                continue;
            }
            String name = imp.getNameAsString();
            if (n == 1) {
                if (name.startsWith(repositoryPackage + ".")) {
                    names.add(simpleName(name));
                }
            } else {
                OptionalInt imported = layerOfImport(name);
                if (imported.isPresent() && imported.getAsInt() < n) {
                    names.add(simpleName(name));
                }
            }
        }
        return names;
    }

    /** CU の import のうち {@code dto/**}（dto.in / dto.out）に該当するものの単純クラス名集合。 */
    private Set<String> dtoSimpleTypeNames(CompilationUnit cu) {
        String dtoPackage = basePackage + ".dto";
        Set<String> names = new HashSet<>();
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isAsterisk()) {
                continue;
            }
            String name = imp.getNameAsString();
            if (name.startsWith(dtoPackage + ".")) {
                names.add(simpleName(name));
            }
        }
        return names;
    }

    /** CU の import のうち外部連携パッケージに該当するものの単純クラス名集合（ワイルドカードは対象外）。 */
    private Set<String> externalSimpleTypeNames(CompilationUnit cu) {
        Set<String> names = new HashSet<>();
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isAsterisk()) {
                continue;
            }
            String name = imp.getNameAsString();
            if (isExternalImport(name)) {
                names.add(simpleName(name));
            }
        }
        return names;
    }

    /** クラス内の変数・パラメータ・フィールド名 → 宣言型の単純名（{@code var} は対象外）のマップ。 */
    private Map<String, String> declaredTypeSimpleNames(CompilationUnit cu) {
        Map<String, String> types = new HashMap<>();
        for (VariableDeclarator v : cu.findAll(VariableDeclarator.class)) {
            types.put(v.getNameAsString(), simpleTypeName(v.getType()));
        }
        for (Parameter p : cu.findAll(Parameter.class)) {
            types.put(p.getNameAsString(), simpleTypeName(p.getType()));
        }
        return types;
    }

    private String simpleTypeName(Type type) {
        return type.isClassOrInterfaceType()
                ? type.asClassOrInterfaceType().getNameAsString()
                : type.asString();
    }

    /** メソッド呼び出しのレシーバが外部クライアント（外部型の変数／フィールド、または外部型の静的呼び出し）か。 */
    private boolean isExternalSink(MethodCallExpr call, Set<String> externalTypes,
                                   Map<String, String> declaredTypes) {
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) {
            return false;
        }
        Expression sc = scope.get();
        String receiver = null;
        if (sc.isNameExpr()) {
            receiver = sc.asNameExpr().getNameAsString();
            // 静的呼び出し（ExternalType.method(..)）
            if (externalTypes.contains(receiver)) {
                return true;
            }
        } else if (sc.isFieldAccessExpr()) {
            // this.client など
            receiver = sc.asFieldAccessExpr().getNameAsString();
        }
        if (receiver == null) {
            return false;
        }
        String declaredType = declaredTypes.get(receiver);
        return declaredType != null && externalTypes.contains(declaredType);
    }

    /** 引数式に含まれる名前参照を辿り、constants/ 由来の値があれば違反として記録する。 */
    private void checkArgForConstants(Expression arg, Path file, Set<String> constantFqns,
                                      Set<String> violations) {
        for (FieldAccessExpr fa : arg.findAll(FieldAccessExpr.class)) {
            traceConstant(fa, file, constantFqns, violations, new HashSet<>(), 0);
        }
        for (NameExpr ne : arg.findAll(NameExpr.class)) {
            traceConstant(ne, file, constantFqns, violations, new HashSet<>(), 0);
        }
    }

    /**
     * 名前参照（{@code NameExpr}/{@code FieldAccessExpr}）の宣言を解決し、constants/ のフィールドへ
     * 到達すれば違反を記録する。ローカル変数・自クラスフィールドの場合は初期化子を辿って別名経由も追う。
     */
    private void traceConstant(Expression leaf, Path file, Set<String> constantFqns,
                               Set<String> violations, Set<Integer> visited, int depth) {
        if (depth > 6) {
            return;
        }
        ResolvedValueDeclaration resolved;
        try {
            resolved = resolveValue(leaf);
        } catch (RuntimeException e) {
            // 解決不能（外部型・型未解決など）は判定不可としてスキップ（誤検知を避ける）
            return;
        }
        if (resolved == null) {
            return;
        }
        if (resolved.isField()) {
            String declaringType = resolved.asField().declaringType().getQualifiedName();
            if (constantFqns.contains(declaringType)) {
                violations.add(loc(file, leaf)
                        + " 外部連携呼び出しに渡る値が constants/ 由来です（"
                        + simpleName(declaringType) + "." + resolved.getName()
                        + "）。接続情報・シークレットは constants/ ではなく application.yaml + 環境変数"
                        + "（@Value / System.getenv）経由で注入してください");
                return;
            }
        }
        // 別名（ローカル変数／自クラスフィールド）の初期化子を辿る
        Optional<Node> ast = resolved.toAst();
        if (ast.isEmpty() || !visited.add(System.identityHashCode(ast.get()))) {
            return;
        }
        for (VariableDeclarator vd : ast.get().findAll(VariableDeclarator.class)) {
            if (!vd.getNameAsString().equals(resolved.getName())) {
                continue;
            }
            vd.getInitializer().ifPresent(init -> {
                for (FieldAccessExpr fa : init.findAll(FieldAccessExpr.class)) {
                    traceConstant(fa, file, constantFqns, violations, visited, depth + 1);
                }
                for (NameExpr ne : init.findAll(NameExpr.class)) {
                    traceConstant(ne, file, constantFqns, violations, visited, depth + 1);
                }
            });
        }
    }

    private ResolvedValueDeclaration resolveValue(Expression leaf) {
        if (leaf.isFieldAccessExpr()) {
            return leaf.asFieldAccessExpr().resolve();
        }
        return leaf.asNameExpr().resolve();
    }

    /**
     * {@code appDir}（{@code .../src/main/java/com/<name>/app}）からソースルート
     * {@code src/main/java} を求める。階層が満たない場合は {@code null}。
     */
    private Path sourceRootOf(Path appDir) {
        Path p = appDir.getParent();      // com/<name>
        if (p != null) {
            p = p.getParent();            // com
        }
        if (p != null) {
            p = p.getParent();            // src/main/java
        }
        return p;
    }

    /** import に外部連携パッケージ（拒否リスト）が1つでも含まれるか。 */
    private boolean hasExternalImport(CompilationUnit cu) {
        for (ImportDeclaration imp : cu.getImports()) {
            if (isExternalImport(imp.getNameAsString())) {
                return true;
            }
        }
        return false;
    }

    /** ディレクトリ（{@code dto/in} 等のサブパス可）とファイル名から FQN を組み立てる。 */
    private String fqnGeneral(Path file, String dir) {
        String pkg = dir.isEmpty() ? basePackage : basePackage + "." + dir.replace('/', '.');
        return pkg + "." + baseName(file);
    }

    private String fqnOf(Path file, String dir) {
        return basePackage + "." + dir + "." + baseName(file);
    }

    private String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    private String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    private String simpleNames(Collection<String> fqns) {
        List<String> names = new ArrayList<>();
        for (String fqn : fqns) {
            names.add(simpleName(fqn));
        }
        return String.join(", ", names);
    }

    /** ルール4.3: repository で state フィールドに持つことを禁止するコレクション型の単純名。 */
    private static final Set<String> COLLECTION_TYPE_NAMES = Set.of(
            "Map", "HashMap", "LinkedHashMap", "TreeMap", "ConcurrentHashMap", "ConcurrentMap",
            "SortedMap", "NavigableMap", "Hashtable", "Properties", "EnumMap", "WeakHashMap", "IdentityHashMap",
            "List", "ArrayList", "LinkedList", "CopyOnWriteArrayList", "Vector", "Stack",
            "Set", "HashSet", "LinkedHashSet", "TreeSet", "SortedSet", "NavigableSet", "EnumSet", "CopyOnWriteArraySet",
            "Collection", "Queue", "Deque", "ArrayDeque", "PriorityQueue", "ConcurrentLinkedQueue");

    /**
     * ルール4.2 / 4.3: repository がインメモリの偽データストアになっていないか検証する。
     *
     * <ul>
     *   <li>4.2: 外部連携 import（拒否リスト {@code external-packages.txt}）を最低1つ持つこと
     *       （ルール15の裏返し）。{@code HashMap} 等で完結する実装は外部 import を持たないため弾く。</li>
     *   <li>4.3: コレクション型（Map/List/Set/Collection 系・配列）を state フィールドとして
     *       保持しないこと。永続状態をプロセス内に溜め込む＝偽ストアの典型症状を直接弾く。</li>
     * </ul>
     */
    private List<String> validateRepositoryNotInMemory(Path file, CompilationUnit cu) {
        List<String> violations = new ArrayList<>();

        // 4.2: 外部連携 import 必須（インメモリ実装は外部 import を持たない）
        if (!hasExternalImport(cu)) {
            violations.add(rel(file)
                    + " repository は外部ツール連携（DB/外部接続等）を実装する必要があります"
                    + "（外部連携パッケージの import が見つかりません）。インメモリ実装ではなく実連携を実装してください");
        }

        // 4.3: コレクションを state フィールドに持つことを禁止
        for (FieldDeclaration fd : cu.findAll(FieldDeclaration.class)) {
            for (VariableDeclarator var : fd.getVariables()) {
                if (isCollectionStateType(var.getType())) {
                    violations.add(loc(file, var)
                            + " repository はコレクション（Map/List/Set/配列）を state フィールドに持てません（"
                            + var.getNameAsString() + ": " + var.getType().asString()
                            + "）。インメモリの偽データストアではなく外部連携を実装してください");
                }
            }
        }
        return violations;
    }

    /** ルール4.3: repository で禁止するコレクション型（配列、または java.util コレクション系の型名）か判定する。 */
    private boolean isCollectionStateType(Type type) {
        if (type.isArrayType()) {
            return true;
        }
        if (type.isClassOrInterfaceType()) {
            return COLLECTION_TYPE_NAMES.contains(type.asClassOrInterfaceType().getNameAsString());
        }
        return false;
    }

    /**
     * ルール4: 各 repository に対応する dto/in・dto/out が存在するか検証する。
     * 対応はステム一致（repository 名から {@code Repository} を除いたステムに対し、
     * {@code <ステム>InDto} / {@code <ステム>OutDto} が存在すること）。
     */
    private List<String> validateRepositoryDtoPairs(List<Path> javaFiles) {
        Set<String> repositories = new TreeSet<>();
        Set<String> dtoIn = new HashSet<>();
        Set<String> dtoOut = new HashSet<>();

        for (Path file : javaFiles) {
            String base = baseName(file);
            switch (parentDir(file)) {
                case "repository" -> repositories.add(base);
                case "dto/in" -> dtoIn.add(base);
                case "dto/out" -> dtoOut.add(base);
                default -> { /* 対象外 */ }
            }
        }

        List<String> violations = new ArrayList<>();
        for (String base : repositories) {
            String stem = repositoryStem(base);
            String expectedIn = stem + DTO_IN_SUFFIX;
            String expectedOut = stem + DTO_OUT_SUFFIX;
            if (!dtoIn.contains(expectedIn)) {
                violations.add("repository/" + base + ".java に対応する dto/in/" + expectedIn + ".java がありません");
            }
            if (!dtoOut.contains(expectedOut)) {
                violations.add("repository/" + base + ".java に対応する dto/out/" + expectedOut + ".java がありません");
            }
        }
        return violations;
    }

    /** repository 名（ベース名）からステムを取り出す（末尾の {@code Repository} を除去。無ければそのまま）。 */
    private String repositoryStem(String repositoryBase) {
        if (repositoryBase.endsWith(REPOSITORY_SUFFIX)
                && repositoryBase.length() > REPOSITORY_SUFFIX.length()) {
            return repositoryBase.substring(0, repositoryBase.length() - REPOSITORY_SUFFIX.length());
        }
        return repositoryBase;
    }

    /**
     * ルール4（imports）: {@code repository/Foo.java} は対応する {@code dto/in/Foo}・{@code dto/out/Foo} を
     * それぞれ<b>ちょうど1件</b> import すること。ファイル存在（{@link #validateRepositoryDtoPairs}）だけでは
     * 「同名 DTO が存在するが repository が実際には使っていない」状態を許してしまうため、import で実利用を担保する。
     *
     * <p><b>既知の限界</b>: ワイルドカード import（{@code import <base>.dto.in.*;}）はベース名・件数を特定
     * できないため、その側の検査をスキップする（誤検知回避。明示 import を推奨）。
     */
    /**
     * ルール1.5: ワイルドカード import（{@code import x.y.*;} / {@code import static x.Y.*;}）を禁止する。
     * 明示 import を強制し、依存の所在を import 行で一意に追えるようにする。件数・ベース名で依存を検査する
     * ルール（例: ルール4 の repository↔DTO「ちょうど1件」検査）がワイルドカードで回避されるのを防ぐ。
     */
    private List<String> validateNoWildcardImports(Path file, CompilationUnit cu) {
        List<String> violations = new ArrayList<>();
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isAsterisk()) {
                String keyword = imp.isStatic() ? "import static " : "import ";
                violations.add(loc(file, imp)
                        + " ワイルドカード import は禁止です。明示的に import してください: "
                        + keyword + imp.getNameAsString() + ".*");
            }
        }
        return violations;
    }

    private List<String> validateRepositoryDtoImports(Path file, CompilationUnit cu) {
        String stem = repositoryStem(baseName(file));
        String dtoInPkg = basePackage + ".dto.in";
        String dtoOutPkg = basePackage + ".dto.out";

        List<String> inImports = new ArrayList<>();
        List<String> outImports = new ArrayList<>();
        boolean inWildcard = false;
        boolean outWildcard = false;
        for (ImportDeclaration imp : cu.getImports()) {
            String name = imp.getNameAsString();
            if (imp.isAsterisk()) {
                inWildcard |= name.equals(dtoInPkg);
                outWildcard |= name.equals(dtoOutPkg);
                continue;
            }
            if (name.startsWith(dtoInPkg + ".")) {
                inImports.add(simpleName(name));
            } else if (name.startsWith(dtoOutPkg + ".")) {
                outImports.add(simpleName(name));
            }
        }

        List<String> violations = new ArrayList<>();
        violations.addAll(checkRepositoryDtoSide(file, stem + DTO_IN_SUFFIX, "dto/in", inImports, inWildcard));
        violations.addAll(checkRepositoryDtoSide(file, stem + DTO_OUT_SUFFIX, "dto/out", outImports, outWildcard));
        return violations;
    }

    /**
     * ルール4（imports）: 片側（dto/in もしくは dto/out）について、対応する DTO 名（ステム＋サフィックス）を
     * ちょうど1件 import するか検証する。
     */
    private List<String> checkRepositoryDtoSide(Path file, String expected, String side,
                                                List<String> imports, boolean wildcard) {
        if (wildcard) {
            return List.of();
        }
        List<String> violations = new ArrayList<>();
        if (!imports.contains(expected)) {
            violations.add(rel(file) + " repository は対応する " + side + "/" + expected
                    + " を import する必要があります（" + basePackage + "." + side.replace('/', '.')
                    + "." + expected + " が見つかりません）");
        }
        if (imports.size() > 1) {
            violations.add(rel(file) + " repository の " + side + " import は1件のみ許可されます（検出: "
                    + String.join(", ", imports) + "）");
        }
        return violations;
    }

    private void flag(CompilationUnit cu, Class<? extends Node> type, String label,
                      Path file, String dir, List<String> violations) {
        for (Node node : cu.findAll(type)) {
            violations.add(loc(file, node) + " " + dir + " では条件・繰り返し処理（" + label + "）は禁止です");
        }
    }

    private boolean isControlFlowForbidden(String dir) {
        return isDtoDir(dir)
                || dir.equals("repository")
                || dir.equals("constants");
    }

    private boolean isDtoDir(String dir) {
        return dir.equals("dto") || dir.startsWith("dto/");
    }

    /**
     * ルール1.2: 非決定的な乱数・ID 生成を禁止する（全レイヤー対象）。
     * その場で非決定的に値を作ると、本来は呼び出し元・永続化層から受け取る／注入すべき値を
     * 握りつぶして「無理やりテストを通す」実装を許してしまい、再現性も損なう。
     * ID は呼び出し元から受け取るか ID 生成器を注入し、乱数が必要な場合は乱数源を注入する
     * （セキュリティ用途は {@code SecureRandom} を使う＝本ルールの対象外）。検出対象:
     * <ul>
     *   <li>{@code UUID.randomUUID()}（FQN {@code java.util.UUID.randomUUID()} / static import 経由の {@code randomUUID()} 含む）</li>
     *   <li>{@code Math.random()}（FQN {@code java.lang.Math.random()} / static import 経由の {@code random()} 含む）</li>
     *   <li>{@code new Random(...)}（{@code java.util.Random}。{@code SecureRandom} は対象外）</li>
     * </ul>
     */
    private List<String> validateNoNondeterministicRandom(Path file, CompilationUnit cu) {
        List<String> violations = new ArrayList<>();
        boolean uuidStatic = staticImported(cu, "java.util.UUID", "randomUUID");
        boolean mathRandomStatic = staticImported(cu, "java.lang.Math", "random");

        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (name.equals("randomUUID")) {
                boolean hit = call.getScope()
                        .map(scope -> simpleName(scope.toString()).equals("UUID"))
                        .orElse(uuidStatic);
                if (hit) {
                    violations.add(loc(file, call)
                            + " UUID.randomUUID() の使用は禁止です。ID は呼び出し元から受け取るか ID 生成器を注入してください"
                            + "（非決定的な実装でのテスト通過・再現性低下を防ぐため）");
                }
            } else if (name.equals("random")) {
                boolean hit = call.getScope()
                        .map(scope -> simpleName(scope.toString()).equals("Math"))
                        .orElse(mathRandomStatic);
                if (hit) {
                    violations.add(loc(file, call)
                            + " Math.random() の使用は禁止です。乱数源を注入し、セキュリティ用途は SecureRandom を使ってください"
                            + "（非決定的な実装でのテスト通過・再現性低下を防ぐため）");
                }
            }
        }
        for (ObjectCreationExpr creation : cu.findAll(ObjectCreationExpr.class)) {
            if (creation.getType().getNameAsString().equals("Random")) {
                violations.add(loc(file, creation)
                        + " java.util.Random の生成は禁止です。乱数源を注入し、セキュリティ用途は SecureRandom を使ってください"
                        + "（非決定的な実装でのテスト通過・再現性低下を防ぐため）");
            }
        }
        return violations;
    }

    /**
     * ルール5.1: null リテラルの使用を制限する（全レイヤー対象）。
     * 許可は「{@code ==} / {@code !=} による null 比較」と「catch ブロック内の {@code return null;}」のみ。
     * 代入・変数/フィールド初期化・関数/コンストラクタ引数・catch 外の return・その他の文脈は禁止する。
     * null を撒くと NPE の温床になり、未実装の握りつぶしにも使われるため、不在は Optional / 例外 /
     * 既定値で表現する。
     */
    private List<String> validateNullUsage(Path file, CompilationUnit cu) {
        List<String> violations = new ArrayList<>();
        for (NullLiteralExpr nul : cu.findAll(NullLiteralExpr.class)) {
            Node parent = nul.getParentNode().orElse(null);

            // 許可1: null 比較（== / !=）
            if (parent instanceof BinaryExpr bin
                    && (bin.getOperator() == BinaryExpr.Operator.EQUALS
                            || bin.getOperator() == BinaryExpr.Operator.NOT_EQUALS)) {
                continue;
            }
            // 許可2: catch ブロック内の return null;（catch 外は禁止）
            if (parent instanceof ReturnStmt) {
                if (nul.findAncestor(CatchClause.class).isPresent()) {
                    continue;
                }
                violations.add(loc(file, nul)
                        + " catch 外での return null は禁止です（不在は Optional / 例外 / 既定値で表現してください。catch 内の return null のみ許可）");
                continue;
            }
            // 禁止: 代入 / 初期化 / 引数 / その他
            String reason;
            if (parent instanceof AssignExpr) {
                reason = "null の代入は禁止です";
            } else if (parent instanceof VariableDeclarator) {
                reason = "null による変数・フィールドの初期化は禁止です";
            } else if (parent instanceof MethodCallExpr || parent instanceof ObjectCreationExpr
                    || parent instanceof ExplicitConstructorInvocationStmt) {
                reason = "null を引数として渡すことは禁止です";
            } else {
                reason = "null の使用は禁止です（許可は ==/!= の比較と catch 内の return null のみ）";
            }
            violations.add(loc(file, nul) + " " + reason + "（不在は Optional / 例外 / 既定値で表現してください）");
        }
        return violations;
    }

    /** {@code import static <owner>.<member>;} または {@code import static <owner>.*;} があるか。 */
    private boolean staticImported(CompilationUnit cu, String owner, String member) {
        return cu.getImports().stream().anyMatch(imp -> imp.isStatic()
                && (imp.getNameAsString().equals(owner + "." + member)
                        || (imp.isAsterisk() && imp.getNameAsString().equals(owner))));
    }

    /**
     * ルール4.1: dto/ 配下の各クラスは Lombok アノテーション（{@link #DTO_LOMBOK_ANNOTATIONS}）を
     * 少なくとも1つ付与し、かつファイルは {@code lombok} パッケージを import すること。
     * record / enum / interface はクラスではないため対象外（record は accessor を言語が生成するため）。
     */
    private List<String> validateDtoLombok(Path file, CompilationUnit cu) {
        List<String> violations = new ArrayList<>();

        List<ClassOrInterfaceDeclaration> dtoClasses = new ArrayList<>();
        for (TypeDeclaration<?> type : cu.getTypes()) {
            if (type instanceof ClassOrInterfaceDeclaration cls && !cls.isInterface()) {
                dtoClasses.add(cls);
            }
        }
        if (dtoClasses.isEmpty()) {
            // record / enum / interface のみ → 対象外
            return violations;
        }

        // ファイルが lombok を import していること（import lombok.*; / import lombok.Data; いずれも可）
        boolean importsLombok = cu.getImports().stream()
                .map(ImportDeclaration::getNameAsString)
                .anyMatch(name -> name.equals("lombok") || name.startsWith("lombok."));
        if (!importsLombok) {
            violations.add(rel(file) + " DTO は Lombok を import する必要があります（例: import lombok.Data;）");
        }

        for (ClassOrInterfaceDeclaration cls : dtoClasses) {
            boolean hasLombok = cls.getAnnotations().stream()
                    .map(a -> simpleName(a.getNameAsString()))
                    .anyMatch(DTO_LOMBOK_ANNOTATIONS::contains);
            if (!hasLombok) {
                violations.add(loc(file, cls) + " DTO クラス " + cls.getNameAsString()
                        + " は Lombok アノテーションの付与が必須です（@Data / @Value 等。手書きの getter/setter は不可）");
            }
        }
        return violations;
    }

    /**
     * ルール1.3: {@code constants/} の定数定義制約。環境非依存の固定値だけを素朴な定数として公開させる。
     * <ul>
     *   <li>(a) DTO（{@code <basePackage>.dto..}）の import 禁止。</li>
     *   <li>(b) フィールドは {@code public static final} 必須。</li>
     *   <li>(c) 型は {@code String} と 8 プリミティブのみ許可（List/Map/配列/その他オブジェクトは不可）。</li>
     *   <li>(d) 初期化子はリテラル（リテラル連結・他定数参照を含む）のみ。
     *       メソッド呼び出し（{@code List.of(..)} 等）/ {@code new} 生成は禁止。</li>
     * </ul>
     */
    private List<String> validateConstantsDefinitions(Path file, CompilationUnit cu) {
        List<String> violations = new ArrayList<>();
        String dtoPackage = basePackage + ".dto";

        // (a) DTO の import 禁止（dto / dto.in / dto.out のいずれも）
        for (ImportDeclaration imp : cu.getImports()) {
            String name = imp.getNameAsString();
            if (name.equals(dtoPackage) || name.startsWith(dtoPackage + ".")) {
                violations.add(loc(file, imp)
                        + " constants/ は DTO を import できません（定数は DTO に依存しない）: " + name);
            }
        }

        for (FieldDeclaration fd : cu.findAll(FieldDeclaration.class)) {
            String names = String.join(", ",
                    fd.getVariables().stream().map(VariableDeclarator::getNameAsString).toList());

            // (b) public static final 必須
            if (!(fd.isPublic() && fd.isStatic() && fd.isFinal())) {
                violations.add(loc(file, fd)
                        + " constants/ のフィールドは public static final で宣言してください（" + names + "）");
            }

            for (VariableDeclarator var : fd.getVariables()) {
                // (c) 型は String / プリミティブのみ
                if (!isAllowedConstantType(var.getType())) {
                    violations.add(loc(file, var)
                            + " constants/ の定数は String またはプリミティブ型のみ許可です（"
                            + var.getNameAsString() + ": " + var.getType().asString()
                            + "）。List/Map/配列等は不可");
                }

                // (d) 初期化子はリテラルのみ（メソッド呼び出し / new 生成を禁止）
                Expression init = var.getInitializer().orElse(null);
                if (init != null
                        && (!init.findAll(MethodCallExpr.class).isEmpty()
                                || !init.findAll(ObjectCreationExpr.class).isEmpty())) {
                    violations.add(loc(file, var)
                            + " constants/ の定数はリテラルで初期化してください（" + var.getNameAsString()
                            + "）。メソッド呼び出し（List.of 等）/ new による生成は禁止");
                }
            }
        }
        return violations;
    }

    /** ルール1.3(c): constants で許可する型（String と 8 プリミティブのみ）か判定する。 */
    private boolean isAllowedConstantType(Type type) {
        if (type.isPrimitiveType()) {
            return true;
        }
        String name = type.asString();
        return name.equals("String") || name.equals("java.lang.String");
    }

    private int span(Node node) {
        return node.getRange().map(range -> range.end.line - range.begin.line + 1).orElse(0);
    }

    private boolean importsLowerLayer(CompilationUnit cu, int currentLayer) {
        for (ImportDeclaration imp : cu.getImports()) {
            OptionalInt imported = layerOfImport(imp.getNameAsString());
            if (imported.isPresent() && imported.getAsInt() < currentLayer) {
                return true;
            }
        }
        return false;
    }

    /** import 名がサービス層（{@code <base>.layer<数値>}）を指す場合、そのレイヤー番号を返す。 */
    private OptionalInt layerOfImport(String importName) {
        String prefix = basePackage + ".layer";
        if (!importName.startsWith(prefix)) {
            return OptionalInt.empty();
        }
        String rest = importName.substring(prefix.length());
        int i = 0;
        while (i < rest.length() && Character.isDigit(rest.charAt(i))) {
            i++;
        }
        // 数字が1桁以上あり、その後はパッケージ末尾('')かサブパス('.')であること
        if (i == 0 || (i < rest.length() && rest.charAt(i) != '.')) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(Integer.parseInt(rest.substring(0, i)));
    }

    /**
     * util から import が禁止されるパッケージ（{@code layer*}・{@code top}・{@code repository}）を
     * 判定し、該当する場合はそのラベルを返す。該当しなければ {@code null}。
     */
    private String utilForbiddenImport(String importName) {
        if (layerOfImport(importName).isPresent()) {
            return "layer";
        }
        String topPackage = basePackage + ".top";
        if (importName.equals(topPackage) || importName.startsWith(topPackage + ".")) {
            return "top";
        }
        if (importName.equals(repositoryPackage) || importName.startsWith(repositoryPackage + ".")) {
            return "repository";
        }
        return null;
    }

    private boolean isExternalImport(String importName) {
        for (String prefix : externalPackages) {
            if (importName.equals(prefix) || importName.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 外部連携パッケージの拒否リストを読み込む。
     * 優先順位: システムプロパティ {@code external.packages} のパス → カレントディレクトリの
     * {@value #EXTERNAL_PACKAGES_FILE} → JAR同梱リソース。
     */
    private static List<String> loadExternalPackages() {
        return readConfig("external.packages", EXTERNAL_PACKAGES_FILE);
    }

    /**
     * シークレット識別子キーワードを読み込む（大文字小文字無視の比較用に小文字化）。
     * 優先順位: システムプロパティ {@code secret.keywords} のパス → カレントディレクトリの
     * {@value #SECRET_KEYWORDS_FILE} → JAR同梱リソース。
     */
    private static List<String> loadSecretKeywords() {
        List<String> keywords = new ArrayList<>();
        for (String keyword : readConfig("secret.keywords", SECRET_KEYWORDS_FILE)) {
            keywords.add(keyword.toLowerCase(Locale.ROOT));
        }
        return keywords;
    }

    /**
     * 1行1値の外部設定ファイルを読み込む（{@code #} 以降コメント・空行無視）。
     * 優先順位: システムプロパティ {@code systemProperty} のパス → カレントディレクトリの
     * {@code fileName} → JAR同梱リソース。
     */
    private static List<String> readConfig(String systemProperty, String fileName) {
        List<String> lines = null;

        String override = System.getProperty(systemProperty);
        try {
            if (override != null) {
                lines = Files.readAllLines(Path.of(override));
            } else {
                Path local = Path.of(fileName);
                if (Files.isRegularFile(local)) {
                    lines = Files.readAllLines(local);
                }
            }
        } catch (IOException e) {
            lines = null;
        }

        if (lines == null) {
            try (InputStream in = CodeRuleValidator.class.getResourceAsStream("/" + fileName)) {
                if (in != null) {
                    lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                            .lines().toList();
                }
            } catch (IOException e) {
                lines = null;
            }
        }

        List<String> values = new ArrayList<>();
        if (lines != null) {
            for (String line : lines) {
                String text = line;
                int hash = text.indexOf('#');
                if (hash >= 0) {
                    text = text.substring(0, hash);
                }
                text = text.trim();
                if (!text.isEmpty()) {
                    values.add(text);
                }
            }
        }
        return values;
    }

    private boolean usesRepository(CompilationUnit cu) {
        for (ImportDeclaration imp : cu.getImports()) {
            String name = imp.getNameAsString();
            if (imp.isAsterisk() ? name.equals(repositoryPackage) : name.startsWith(repositoryPackage + ".")) {
                return true;
            }
        }
        return false;
    }

    /** 文字列が SQL 文（{@code SELECT}/{@code INSERT} 等で始まる）かどうかを判定する。 */
    private boolean isSqlLiteral(String value) {
        return SQL_LITERAL.matcher(value).matches();
    }

    /** 識別子名がシークレットキーワード（大文字小文字無視の部分一致）に該当するか。 */
    private boolean matchesSecretKeyword(String identifier) {
        String lower = identifier.toLowerCase(Locale.ROOT);
        for (String keyword : secretKeywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 値そのものがシークレットらしい形式（秘密鍵・APIキー等）に一致するか。 */
    private boolean looksLikeSecretValue(String value) {
        for (Pattern pattern : SECRET_VALUE_PATTERNS) {
            if (pattern.matcher(value).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean isFixedLiteral(Expression expr) {
        if (expr instanceof StringLiteralExpr
                || expr instanceof IntegerLiteralExpr
                || expr instanceof LongLiteralExpr
                || expr instanceof DoubleLiteralExpr
                || expr instanceof CharLiteralExpr
                || expr instanceof BooleanLiteralExpr) {
            return true;
        }
        // 符号付き数値リテラル（例: -1, +2.0）も固定値とみなす。null は許可。
        if (expr instanceof UnaryExpr unary
                && (unary.getOperator() == UnaryExpr.Operator.MINUS
                        || unary.getOperator() == UnaryExpr.Operator.PLUS)) {
            Expression inner = unary.getExpression();
            return inner instanceof IntegerLiteralExpr
                    || inner instanceof LongLiteralExpr
                    || inner instanceof DoubleLiteralExpr;
        }
        return false;
    }

    private String rel(Path file) {
        return appDir.relativize(file).toString().replace('\\', '/');
    }

    private String loc(Path file, Node node) {
        int line = node.getBegin().map(pos -> pos.line).orElse(-1);
        return rel(file) + ":" + line;
    }

    private String parentDir(Path file) {
        Path parent = appDir.relativize(file).getParent();
        return parent == null ? "" : parent.toString().replace('\\', '/');
    }

    private String baseName(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - ".java".length());
    }

    private String preview(String value) {
        String oneLine = value.replace("\r", "").replace("\n", "\\n");
        return oneLine.length() > 30 ? oneLine.substring(0, 30) + "…" : oneLine;
    }
}
