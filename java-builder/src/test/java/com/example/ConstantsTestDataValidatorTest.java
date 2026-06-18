package com.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConstantsTestDataValidator} の単体テスト（テスト構成ルール19: 答え合わせ漏洩の検出）。
 *
 * <p>{@code src/main} の {@code constants/} 定数値が {@code src/test} にリテラルとして重複した場合に
 * 発火することと、重複しない／短すぎる値・参照のみのケースで誤検知しないことを検証する。
 */
class ConstantsTestDataValidatorTest {

    private static final String NEEDLE = "テストにリテラルとして重複しています";

    /** constants/ に1ファイル書き出す。 */
    private void writeConstants(Path root, String content) throws Exception {
        Path file = root.resolve("src/main/java/com/demo/app/constants/C.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** src/test に1ファイル書き出す。 */
    private void writeTest(Path root, String content) throws Exception {
        Path file = root.resolve("src/test/java/com/demo/FooTest.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private List<String> validate(Path root) throws Exception {
        return new ConstantsTestDataValidator(root).validate();
    }

    private static boolean has(List<String> violations, String needle) {
        return violations.stream().anyMatch(s -> s.contains(needle));
    }

    @Test
    @DisplayName("constants の値がテストにリテラル重複していれば違反")
    void duplicatedValueForbidden(@TempDir Path root) throws Exception {
        writeConstants(root,
                "package com.demo.app.constants;\n"
                        + "public class C { public static final String NAME = \"taro-yamada\"; }\n");
        writeTest(root,
                "package com.demo;\nclass FooTest { String x = \"taro-yamada\"; }\n");
        List<String> v = validate(root);
        assertTrue(has(v, NEEDLE), () -> v.toString());
        assertTrue(has(v, "taro-yamada"), () -> v.toString());
    }

    @Test
    @DisplayName("テスト側が定数を import 参照（リテラル重複なし）なら違反なし")
    void symbolicReferenceAllowed(@TempDir Path root) throws Exception {
        writeConstants(root,
                "package com.demo.app.constants;\n"
                        + "public class C { public static final String NAME = \"taro-yamada\"; }\n");
        writeTest(root,
                "package com.demo;\nimport com.demo.app.constants.C;\n"
                        + "class FooTest { String x = C.NAME; }\n");
        List<String> v = validate(root);
        assertFalse(has(v, NEEDLE), () -> "想定外の違反: " + v);
    }

    @Test
    @DisplayName("短い値（しきい値未満）はテスト重複しても違反なし")
    void shortValueAllowed(@TempDir Path root) throws Exception {
        writeConstants(root,
                "package com.demo.app.constants;\n"
                        + "public class C { public static final String OK = \"OK\"; }\n");
        writeTest(root,
                "package com.demo;\nclass FooTest { String x = \"OK\"; }\n");
        List<String> v = validate(root);
        assertFalse(has(v, NEEDLE), () -> "短い値が誤検知: " + v);
    }

    @Test
    @DisplayName("constants 以外（テスト同士の重複等）は対象外")
    void nonConstantsValueAllowed(@TempDir Path root) throws Exception {
        // main 側は constants/ ではなく log/ に置く（constants 検査の対象外）。
        Path log = root.resolve("src/main/java/com/demo/app/log/L.java");
        Files.createDirectories(log.getParent());
        Files.writeString(log,
                "package com.demo.app.log;\nclass L { String m(){ return \"unique-value-xyz\"; } }\n");
        writeTest(root,
                "package com.demo;\nclass FooTest { String x = \"unique-value-xyz\"; }\n");
        List<String> v = validate(root);
        assertFalse(has(v, NEEDLE), () -> "constants 外が誤検知: " + v);
    }
}
