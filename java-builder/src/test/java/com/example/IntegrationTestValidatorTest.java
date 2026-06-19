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
 * {@link IntegrationTestValidator} の単体テスト（テスト構成ルール18: エンドポイント網羅）。
 *
 * <p>ルール17（統合テストディレクトリ／{@code .java} の存在）は {@code directory-checker} へ移行したため、
 * 本テストは OpenAPI エンドポイントのハードコード網羅（ルール18・OpenAPI 指定時のみ）の発火を検証する。
 */
class IntegrationTestValidatorTest {

    private static final String PROJECT = "demo";

    private Path integrationDir(Path root) {
        return root.resolve("src/test/java/com").resolve(PROJECT).resolve("integration");
    }

    /** integration 配下に1ファイル書き出す。 */
    private void writeIntegration(Path root, String name, String content) throws Exception {
        Path file = integrationDir(root).resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private List<String> validate(Path root, List<String> endpoints) throws Exception {
        return new IntegrationTestValidator(root, PROJECT, endpoints).validate();
    }

    private static boolean has(List<String> violations, String needle) {
        return violations.stream().anyMatch(s -> s.contains(needle));
    }

    @Test
    @DisplayName("OpenAPI 未指定（endpoints 空）なら検査なし")
    void noOpenApiNoCheck(@TempDir Path root) throws Exception {
        // ディレクトリが無くてもルール17は directory-checker の責務のため、ここでは違反を出さない。
        List<String> v = validate(root, List.of());
        assertTrue(v.isEmpty(), () -> "想定外の違反: " + v);
    }

    @Test
    @DisplayName("ルール18: integration ディレクトリが無ければ全エンドポイント未網羅")
    void missingDirUncovered(@TempDir Path root) throws Exception {
        List<String> v = validate(root, List.of("/health"));
        assertTrue(has(v, "エンドポイント /health"), () -> v.toString());
    }

    @Test
    @DisplayName("ルール18: エンドポイントが現れなければ違反")
    void uncoveredEndpointForbidden(@TempDir Path root) throws Exception {
        writeIntegration(root, "LoginIT.java",
                "package com.demo.integration;\nclass LoginIT { String p = \"/accounts/login\"; }\n");
        List<String> v = validate(root, List.of("/accounts/login", "/health"));
        assertTrue(has(v, "エンドポイント /health"), () -> v.toString());
        assertFalse(has(v, "エンドポイント /accounts/login"), () -> "網羅済みが誤検知: " + v);
    }

    @Test
    @DisplayName("ルール18: 全エンドポイントが現れれば違反なし")
    void allEndpointsCoveredAllowed(@TempDir Path root) throws Exception {
        writeIntegration(root, "ApiIT.java",
                "package com.demo.integration;\nclass ApiIT {\n"
                        + "  String a = \"/accounts/login\";\n"
                        + "  String b = \"/health\";\n}\n");
        List<String> v = validate(root, List.of("/accounts/login", "/health"));
        assertTrue(v.isEmpty(), () -> "想定外の違反: " + v);
    }
}
