package com.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CodeRuleValidator} のコード内容ルールのうち、AST 検査で発火するルールの単体テスト。
 *
 * <p>各テストは検証対象の {@code .java} を一時ディレクトリに1ファイル書き出し、{@code validate()} の
 * 違反一覧を検査する。レイヤー依存など他ルールに巻き込まれないよう、制約の少ない {@code log/} に
 * 置き、対象ルール固有のメッセージ断片で照合する（1テスト＝1つの失敗要因）。
 */
class CodeRuleValidatorTest {

    private static final String BASE_PACKAGE = "com.demo.app";

    /** {@code app/<rel>} に内容を書き出し、検証結果（違反一覧）を返す。 */
    private List<String> validate(Path app, String rel, String content) throws Exception {
        Path file = app.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return new CodeRuleValidator(app, BASE_PACKAGE).validate();
    }

    private static boolean has(List<String> violations, String needle) {
        return violations.stream().anyMatch(s -> s.contains(needle));
    }

    @Nested
    @DisplayName("ルール5.1: null の使用制限")
    class NullUsage {

        @Test
        @DisplayName("== / != の null 比較は許可")
        void comparisonAllowed(@TempDir Path app) throws Exception {
            List<String> v = validate(app, "log/N.java",
                    "package com.demo.app.log;\npublic class N { boolean m(Object x){ return x == null; } }\n");
            assertTrue(v.isEmpty(), () -> "想定外の違反: " + v);
        }

        @Test
        @DisplayName("catch 内の return null は許可")
        void returnNullInCatchAllowed(@TempDir Path app) throws Exception {
            List<String> v = validate(app, "log/N.java",
                    "package com.demo.app.log;\npublic class N { Object m(Object x){ try { return x; } catch (Exception e) { return null; } } }\n");
            assertTrue(v.isEmpty(), () -> "想定外の違反: " + v);
        }

        @Test
        @DisplayName("catch 外の return null は禁止")
        void returnNullOutsideCatchForbidden(@TempDir Path app) throws Exception {
            List<String> v = validate(app, "log/N.java",
                    "package com.demo.app.log;\npublic class N { Object m(){ return null; } }\n");
            assertTrue(has(v, "catch 外での return null は禁止"), () -> v.toString());
        }

        @Test
        @DisplayName("null の代入は禁止")
        void assignNullForbidden(@TempDir Path app) throws Exception {
            List<String> v = validate(app, "log/N.java",
                    "package com.demo.app.log;\npublic class N { void m(Object x){ x = null; } }\n");
            assertTrue(has(v, "null の代入は禁止"), () -> v.toString());
        }

        @Test
        @DisplayName("null による初期化は禁止")
        void initNullForbidden(@TempDir Path app) throws Exception {
            List<String> v = validate(app, "log/N.java",
                    "package com.demo.app.log;\npublic class N { void m(){ Object y = null; } }\n");
            assertTrue(has(v, "null による変数・フィールドの初期化は禁止"), () -> v.toString());
        }

        @Test
        @DisplayName("null を引数に渡すのは禁止")
        void nullArgumentForbidden(@TempDir Path app) throws Exception {
            List<String> v = validate(app, "log/N.java",
                    "package com.demo.app.log;\npublic class N { String m(){ return java.util.Objects.toString(null); } }\n");
            assertTrue(has(v, "null を引数として渡すことは禁止"), () -> v.toString());
        }

        @Test
        @DisplayName("三項の null 枝は禁止（比較は許可）")
        void ternaryNullBranchForbidden(@TempDir Path app) throws Exception {
            List<String> v = validate(app, "log/N.java",
                    "package com.demo.app.log;\npublic class N { Object m(Object x){ return x != null ? x : null; } }\n");
            assertTrue(has(v, "null の使用は禁止"), () -> v.toString());
        }
    }

    @Nested
    @DisplayName("ルール1.2: 非決定的な乱数・ID 生成の禁止")
    class Nondeterministic {

        @Test
        @DisplayName("UUID.randomUUID() は禁止")
        void randomUuidForbidden(@TempDir Path app) throws Exception {
            List<String> v = validate(app, "log/R.java",
                    "package com.demo.app.log;\nimport java.util.UUID;\npublic class R { Object m(){ return UUID.randomUUID(); } }\n");
            assertTrue(has(v, "UUID.randomUUID() の使用は禁止"), () -> v.toString());
        }

        @Test
        @DisplayName("Math.random() は禁止")
        void mathRandomForbidden(@TempDir Path app) throws Exception {
            List<String> v = validate(app, "log/R.java",
                    "package com.demo.app.log;\npublic class R { double m(){ return Math.random(); } }\n");
            assertTrue(has(v, "Math.random() の使用は禁止"), () -> v.toString());
        }

        @Test
        @DisplayName("new java.util.Random は禁止")
        void newRandomForbidden(@TempDir Path app) throws Exception {
            List<String> v = validate(app, "log/R.java",
                    "package com.demo.app.log;\nimport java.util.Random;\npublic class R { int m(){ return new Random().nextInt(); } }\n");
            assertTrue(has(v, "java.util.Random の生成は禁止"), () -> v.toString());
        }

        @Test
        @DisplayName("SecureRandom は許可")
        void secureRandomAllowed(@TempDir Path app) throws Exception {
            List<String> v = validate(app, "log/R.java",
                    "package com.demo.app.log;\nimport java.security.SecureRandom;\npublic class R { int m(){ return new SecureRandom().nextInt(); } }\n");
            assertTrue(v.isEmpty(), () -> "想定外の違反: " + v);
        }
    }

    @Nested
    @DisplayName("ルール4.1: DTO は Lombok 必須")
    class DtoLombok {

        @Test
        @DisplayName("Lombok 注釈・import が無い DTO は禁止")
        void dtoWithoutLombokForbidden(@TempDir Path app) throws Exception {
            List<String> v = validate(app, "dto/in/Order.java",
                    "package com.demo.app.dto.in;\npublic class Order { private String id; }\n");
            assertTrue(has(v, "Lombok アノテーションの付与が必須"), () -> v.toString());
            assertTrue(has(v, "Lombok を import する必要があります"), () -> v.toString());
        }

        @Test
        @DisplayName("@Data + import lombok の DTO は許可")
        void dtoWithLombokAllowed(@TempDir Path app) throws Exception {
            List<String> v = validate(app, "dto/in/Order.java",
                    "package com.demo.app.dto.in;\nimport lombok.Data;\n@Data\npublic class Order { private String id; }\n");
            assertFalse(has(v, "Lombok"), () -> "想定外の Lombok 違反: " + v);
        }
    }

    @Nested
    @DisplayName("ルール1.3: constants の定数定義制約")
    class ConstantsDefinitions {

        @Test
        @DisplayName("public static final な String/プリミティブ・リテラル初期化は許可")
        void validConstantsAllowed(@TempDir Path app) throws Exception {
            List<String> v = validate(app, "constants/C.java",
                    "package com.demo.app.constants;\npublic class C {\n"
                            + "  public static final String NAME = \"x\";\n"
                            + "  public static final int MAX = 10;\n"
                            + "  public static final String COMBO = \"a\" + \"b\";\n"
                            + "}\n");
            assertFalse(has(v, "constants/"), () -> "想定外の constants 違反: " + v);
        }

        @Test
        @DisplayName("(a) DTO の import は禁止")
        void dtoImportForbidden(@TempDir Path app) throws Exception {
            List<String> v = validate(app, "constants/C.java",
                    "package com.demo.app.constants;\nimport com.demo.app.dto.in.Order;\n"
                            + "public class C { public static final String NAME = \"x\"; }\n");
            assertTrue(has(v, "DTO を import できません"), () -> v.toString());
        }

        @Test
        @DisplayName("(b) public static final を欠くフィールドは禁止")
        void nonStaticFinalForbidden(@TempDir Path app) throws Exception {
            List<String> v = validate(app, "constants/C.java",
                    "package com.demo.app.constants;\npublic class C { public final String NAME = \"x\"; }\n");
            assertTrue(has(v, "public static final で宣言してください"), () -> v.toString());
        }

        @Test
        @DisplayName("(c) String/プリミティブ以外の型は禁止")
        void nonBasicTypeForbidden(@TempDir Path app) throws Exception {
            List<String> v = validate(app, "constants/C.java",
                    "package com.demo.app.constants;\npublic class C { public static final Object O = \"x\"; }\n");
            assertTrue(has(v, "String またはプリミティブ型のみ許可"), () -> v.toString());
        }

        @Test
        @DisplayName("(d) メソッド呼び出しによる初期化は禁止")
        void methodCallInitializerForbidden(@TempDir Path app) throws Exception {
            List<String> v = validate(app, "constants/C.java",
                    "package com.demo.app.constants;\npublic class C { public static final String S = String.valueOf(1); }\n");
            assertTrue(has(v, "リテラルで初期化してください"), () -> v.toString());
        }
    }
}
