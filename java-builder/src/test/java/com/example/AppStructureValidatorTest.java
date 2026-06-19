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
 * {@link AppStructureValidator} の構造ルールのうち、命名サフィックス（ルールD5）の単体テスト。
 *
 * <p>各テストは {@code app/} 配下に最小のファイルを書き出し、{@code validate()} の違反一覧を
 * 対象ルール固有のメッセージ断片で照合する（1テスト＝1つの失敗要因）。
 */
class AppStructureValidatorTest {

    /** {@code app/<rel>} に空クラスを書き出す。 */
    private void writeJava(Path app, String rel) throws Exception {
        Path file = app.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package com.demo;\npublic class C {}\n");
    }

    private static boolean has(List<String> violations, String needle) {
        return violations.stream().anyMatch(s -> s.contains(needle));
    }

    @Nested
    @DisplayName("ルールD5: 命名サフィックス")
    class NamingSuffix {

        @Test
        @DisplayName("repository は *Repository でなければ違反")
        void repositorySuffixRequired(@TempDir Path app) throws Exception {
            writeJava(app, "repository/Account.java");
            List<String> v = new AppStructureValidator(app).validate();
            assertTrue(has(v, "repository/Account.java は命名サフィックス \"Repository\""), () -> v.toString());
        }

        @Test
        @DisplayName("dto/in は *InDto でなければ違反")
        void dtoInSuffixRequired(@TempDir Path app) throws Exception {
            writeJava(app, "dto/in/Account.java");
            List<String> v = new AppStructureValidator(app).validate();
            assertTrue(has(v, "dto/in/Account.java は命名サフィックス \"InDto\""), () -> v.toString());
        }

        @Test
        @DisplayName("dto/out は *OutDto でなければ違反")
        void dtoOutSuffixRequired(@TempDir Path app) throws Exception {
            writeJava(app, "dto/out/Account.java");
            List<String> v = new AppStructureValidator(app).validate();
            assertTrue(has(v, "dto/out/Account.java は命名サフィックス \"OutDto\""), () -> v.toString());
        }

        @Test
        @DisplayName("サフィックスのみ（ステムが空）も違反")
        void suffixOnlyForbidden(@TempDir Path app) throws Exception {
            writeJava(app, "repository/Repository.java");
            List<String> v = new AppStructureValidator(app).validate();
            assertTrue(has(v, "repository/Repository.java は命名サフィックス \"Repository\""), () -> v.toString());
        }

        @Test
        @DisplayName("正しいサフィックス（Repository/InDto/OutDto）なら命名違反なし")
        void correctSuffixesAllowed(@TempDir Path app) throws Exception {
            writeJava(app, "repository/AccountRepository.java");
            writeJava(app, "dto/in/AccountInDto.java");
            writeJava(app, "dto/out/AccountOutDto.java");
            List<String> v = new AppStructureValidator(app).validate();
            assertFalse(has(v, "命名サフィックス"), () -> "想定外の命名違反: " + v);
        }
    }
}
