package com.example;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * ルール19: {@code constants/} の定数値とテストコードのリテラルの重複を検出する（答え合わせ漏洩の検出）。
 *
 * <p>背景: {@code constants/} は文字列リテラルが唯一許される場所（ルール1）であり、かつ定数参照の
 * {@code return} は固定値 return 禁止（ルール5）の対象外でもある。このため、テストの期待値（テストデータ）を
 * {@code constants/} に逃がし、layer から {@code return Const.X;} で返して「無理やりテストを通す」抜け道が
 * 成立しうる。ルール 1.1（テストダブル禁止語）・4.2/4.3（repository のインメモリ実装禁止）と同種の握りつぶしである。
 *
 * <p>検出方針: {@code src/main} の {@code constants/} で定義された文字列定数の<b>値</b>が、
 * {@code src/test} に<b>リテラルとして重複</b>して現れたらエラーとする。テストは定数を import して
 * 参照すべきで、同じ値を main と test の双方にベタ書きするのは「期待値を両側に焼き込む」ズルの署名である。
 *
 * <p><b>誤検知の抑制（チューニング）</b>:
 * <ul>
 *   <li>String 定数のみを対象とする（プリミティブの {@code 0}/{@code 1}/{@code true} 等は除外）。</li>
 *   <li>長さが {@value #DEFAULT_MIN_LENGTH} 文字未満（既定）の値は除外する（"OK"/"NG" 等の汎用トークン対策）。
 *       しきい値はシステムプロパティ {@code -Dconstants.testdata.minlen} で調整可能。</li>
 *   <li>照合は文字列リテラル値の<b>完全一致</b>（識別子の部分一致ではない）。</li>
 * </ul>
 *
 * <p><b>既知の限界</b>: (1) リテラル連結（{@code "a" + "b"}）で組み立てた定数は連結後の値では照合しない。
 * (2) {@code validation/} は誤検知（エラーメッセージのテスト側ベタ書き）が多いため意図的に対象外
 * （constants/ のみ検査する）。完全検出ではなくズルのコストを上げる補助検査であり、真のバックストップは
 * 結合テスト（ルール17/18）が実 repository・外部連携を通すことにある。
 */
public final class ConstantsTestDataValidator {

    /** 照合対象とする定数値の最小長（既定値）。これ未満の短い値は汎用トークンとみなし除外する。 */
    private static final int DEFAULT_MIN_LENGTH = 4;

    private final Path root;
    private final Path srcRoot;
    private final int minLength;

    public ConstantsTestDataValidator(Path root) {
        this(root, root.resolve(BuildConfig.DEFAULT_SRC_ROOT));
    }

    /**
     * @param root    検証対象プロジェクトのルート（違反メッセージの相対パス表示に使用）
     * @param srcRoot ソースルート（{@code root} 配下。従来の固定 {@code src} に相当）
     */
    public ConstantsTestDataValidator(Path root, Path srcRoot) {
        this.root = root;
        this.srcRoot = srcRoot;
        this.minLength = loadMinLength();
    }

    public List<String> validate() throws IOException {
        List<String> violations = new ArrayList<>();

        Path mainJava = srcRoot.resolve("main").resolve("java");
        Path testJava = srcRoot.resolve("test").resolve("java");
        if (!Files.isDirectory(mainJava) || !Files.isDirectory(testJava)) {
            return violations;
        }

        // src/test 配下の全文字列リテラル値 -> 最初に現れた位置（file:line）。
        Map<String, String> testLiterals = collectStringLiterals(testJava, false);
        if (testLiterals.isEmpty()) {
            return violations;
        }

        // src/main の constants/ 配下の定数値 -> 定義位置（file:line）。
        Map<String, String> constantValues = collectStringLiterals(mainJava, true);

        for (Map.Entry<String, String> e : constantValues.entrySet()) {
            String value = e.getKey();
            String testLoc = testLiterals.get(value);
            if (testLoc != null) {
                violations.add(e.getValue()
                        + " constants の定数値がテストにリテラルとして重複しています（テストデータの可能性）: \""
                        + value + "\"（重複箇所: " + testLoc
                        + "）。テストは定数を import して参照し、値を両側にベタ書きしないでください");
            }
        }
        return violations;
    }

    /**
     * 指定ツリー配下の {@code .java} から文字列リテラル値を収集する。
     *
     * @param treeRoot      走査起点（{@code src/main/java} または {@code src/test/java}）
     * @param constantsOnly {@code true} の場合、パスに {@code constants/} ディレクトリを含むファイルのみ対象
     * @return 文字列リテラル値 -> 最初に現れた位置（{@code root} からの相対 {@code file:line}）
     */
    private Map<String, String> collectStringLiterals(Path treeRoot, boolean constantsOnly)
            throws IOException {
        Map<String, String> literals = new LinkedHashMap<>();

        List<Path> javaFiles;
        try (Stream<Path> stream = Files.walk(treeRoot)) {
            javaFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> !constantsOnly || isUnderConstants(p))
                    .sorted()
                    .toList();
        }

        ParserConfiguration config =
                new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_21);
        JavaParser parser = new JavaParser(config);

        for (Path file : javaFiles) {
            ParseResult<CompilationUnit> result = parser.parse(file);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                // 構文解析エラーは CodeRuleValidator 側で別途報告されるためここでは無視する。
                continue;
            }
            CompilationUnit cu = result.getResult().get();
            for (StringLiteralExpr lit : cu.findAll(StringLiteralExpr.class)) {
                String value = lit.getValue();
                if (value.strip().length() < minLength) {
                    continue;
                }
                literals.putIfAbsent(value, rel(file) + ":" + lineOf(lit));
            }
        }
        return literals;
    }

    /** パスに {@code constants} ディレクトリ要素を含むか判定する。 */
    private boolean isUnderConstants(Path file) {
        for (Path part : file) {
            if (part.toString().equals("constants")) {
                return true;
            }
        }
        return false;
    }

    private int lineOf(StringLiteralExpr lit) {
        return lit.getBegin().map((Position pos) -> pos.line).orElse(0);
    }

    private String rel(Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /** 照合対象の最小長を読み込む。{@code -Dconstants.testdata.minlen} で上書き可能。 */
    private static int loadMinLength() {
        String override = System.getProperty("constants.testdata.minlen");
        if (override != null) {
            try {
                int v = Integer.parseInt(override.trim());
                if (v >= 1) {
                    return v;
                }
            } catch (NumberFormatException ignored) {
                // 不正値は既定値にフォールバックする。
            }
        }
        return DEFAULT_MIN_LENGTH;
    }
}
