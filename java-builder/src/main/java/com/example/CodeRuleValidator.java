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
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
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
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.stmt.DoStmt;
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
 *   <li>固定値（リテラル）の return を禁止（{@code return null;} は許可）。
 *       {@code constants/}・{@code validation/} は対象外。</li>
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

            // ルール5: 固定値（リテラル）の return 禁止（null は許可）
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

    /** ルール4: 各 repository に対応する dto/in・dto/out（同名）が存在するか検証する。 */
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
            if (!dtoIn.contains(base)) {
                violations.add("repository/" + base + ".java に対応する dto/in/" + base + ".java がありません");
            }
            if (!dtoOut.contains(base)) {
                violations.add("repository/" + base + ".java に対応する dto/out/" + base + ".java がありません");
            }
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
