package com.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link BuildConfig} の単体テスト（ソースルートを固定 {@code src} ではなく設定から解決する）。
 *
 * <p>環境変数 {@code JAVA_BUILDER_SRC_ROOT} は JUnit から設定できないため、ここでは
 * application.yaml（{@code -Dapplication.config} で場所を指定）と既定値フォールバックを検証する。
 * 環境変数が未設定の前提で実行する（CI/ローカルとも通常は未設定）。
 */
class BuildConfigTest {

    private static final String CONFIG_PROPERTY = "application.config";

    /** 指定 yaml を application.config に設定して関数を実行し、後始末でプロパティを必ず戻す。 */
    private <T> T withConfig(Path yaml, java.util.function.Supplier<T> body) {
        String previous = System.getProperty(CONFIG_PROPERTY);
        System.setProperty(CONFIG_PROPERTY, yaml.toString());
        try {
            return body.get();
        } finally {
            if (previous == null) {
                System.clearProperty(CONFIG_PROPERTY);
            } else {
                System.setProperty(CONFIG_PROPERTY, previous);
            }
        }
    }

    @Test
    @DisplayName("application.yaml の src-root を読む")
    void readsFromApplicationYaml(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("application.yaml");
        Files.writeString(yaml, "src-root: source\n");
        String rel = withConfig(yaml, BuildConfig::srcRootRelative);
        assertEquals("source", rel);
    }

    @Test
    @DisplayName("root 相対の入れ子パスも解決する")
    void resolvesNestedSrcRoot(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("application.yaml");
        Files.writeString(yaml, "src-root: modules/app/src\n");
        Path root = dir.resolve("project");
        Path srcRoot = withConfig(yaml, () -> BuildConfig.resolveSrcRoot(root));
        assertEquals(root.resolve("modules").resolve("app").resolve("src"), srcRoot);
    }

    @Test
    @DisplayName("src-root キーが無ければ既定 src にフォールバック")
    void fallsBackWhenKeyMissing(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("application.yaml");
        Files.writeString(yaml, "other-key: foo\n");
        String rel = withConfig(yaml, BuildConfig::srcRootRelative);
        assertEquals(BuildConfig.DEFAULT_SRC_ROOT, rel);
    }

    @Test
    @DisplayName("設定ファイルが無ければ既定 src にフォールバック")
    void fallsBackWhenNoConfig(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist.yaml");
        String rel = withConfig(missing, BuildConfig::srcRootRelative);
        assertEquals(BuildConfig.DEFAULT_SRC_ROOT, rel);
    }
}
