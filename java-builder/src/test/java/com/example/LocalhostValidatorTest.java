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
 * {@link LocalhostValidator} の単体テスト（コードルール1.4: localhost のハードコード禁止）。
 *
 * <p>main/test 双方の {@code .java} を対象とし、大文字小文字無視の部分一致で検出することを検証する。
 */
class LocalhostValidatorTest {

    /** {@code <root>/<rel>} に内容を書き出す。 */
    private void write(Path root, String rel, String content) throws Exception {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private List<String> validate(Path root) throws Exception {
        return new LocalhostValidator(root).validate();
    }

    private static boolean has(List<String> violations, String needle) {
        return violations.stream().anyMatch(s -> s.contains(needle));
    }

    @Test
    @DisplayName("main の文字列リテラルの localhost は禁止")
    void mainLiteralForbidden(@TempDir Path root) throws Exception {
        write(root, "src/main/java/com/demo/app/constants/C.java",
                "package com.demo.app.constants;\npublic class C { public static final String H = \"localhost\"; }\n");
        List<String> v = validate(root);
        assertTrue(has(v, "'localhost' のハードコードは禁止"), () -> v.toString());
    }

    @Test
    @DisplayName("test 配下の localhost も禁止（test も対象）")
    void testTreeForbidden(@TempDir Path root) throws Exception {
        write(root, "src/test/java/com/demo/integration/IT.java",
                "package com.demo.integration;\nclass IT { String url = \"http://localhost:8080\"; }\n");
        List<String> v = validate(root);
        assertTrue(has(v, "'localhost' のハードコードは禁止"), () -> v.toString());
    }

    @Test
    @DisplayName("大文字小文字を無視して検出する")
    void caseInsensitive(@TempDir Path root) throws Exception {
        write(root, "src/main/java/com/demo/app/log/L.java",
                "package com.demo.app.log;\n// connect to LOCALHOST here\npublic class L {}\n");
        List<String> v = validate(root);
        assertTrue(has(v, "'localhost' のハードコードは禁止"), () -> v.toString());
    }

    @Test
    @DisplayName("ソースルートが src 以外（source）でも検出する")
    void alternateSrcRoot(@TempDir Path root) throws Exception {
        write(root, "source/main/java/com/demo/app/constants/C.java",
                "package com.demo.app.constants;\npublic class C { public static final String H = \"localhost\"; }\n");
        // 既定 "src" 配下には何も置かないため、src 固定だと検出されないことを保証する。
        List<String> v = new LocalhostValidator(root, root.resolve("source")).validate();
        assertTrue(has(v, "'localhost' のハードコードは禁止"), () -> v.toString());
    }

    @Test
    @DisplayName("localhost を含まなければ違反なし")
    void absentAllowed(@TempDir Path root) throws Exception {
        write(root, "src/main/java/com/demo/app/log/L.java",
                "package com.demo.app.log;\npublic class L {}\n");
        write(root, "src/test/java/com/demo/integration/IT.java",
                "package com.demo.integration;\nclass IT {}\n");
        List<String> v = validate(root);
        assertFalse(has(v, "localhost"), () -> "想定外の違反: " + v);
    }
}
