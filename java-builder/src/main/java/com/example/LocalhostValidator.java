package com.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * すべての Java ソース（{@code src/main/java}・{@code src/test/java} 双方）に
 * {@code localhost} のハードコードが含まれていないか検証する（コードルール 1.4）。
 *
 * <p>接続先（ホスト）は環境依存の設定値であり、{@code application.yaml} + 環境変数経由で注入すべきで、
 * ソースに直書きしてはならない。判定は大文字小文字を無視した部分一致で、コメント・識別子・文字列リテラル
 * などソーステキスト全体が対象（例外なし）。{@code ProhibitedWordValidator}（{@code main} 限定の
 * dummy/mock 等）とは異なり、本検査は <b>test も含めた全 {@code .java}</b> を対象とする。
 */
public final class LocalhostValidator {

    private static final String NEEDLE = "localhost";

    private final Path root;

    public LocalhostValidator(Path root) {
        this.root = root;
    }

    public List<String> validate() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String stage : List.of("main", "test")) {
            Path javaRoot = root.resolve("src").resolve(stage).resolve("java");
            if (!Files.isDirectory(javaRoot)) {
                continue;
            }

            List<Path> javaFiles;
            try (Stream<Path> stream = Files.walk(javaRoot)) {
                javaFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .toList();
            }

            for (Path file : javaFiles) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).toLowerCase(Locale.ROOT).contains(NEEDLE)) {
                        violations.add(rel(file) + ":" + (i + 1)
                                + " 'localhost' のハードコードは禁止です（main/test 全体で禁止。"
                                + "接続先は application.yaml + 環境変数経由で注入してください）");
                    }
                }
            }
        }
        return violations;
    }

    /** root からの相対パス（区切りを {@code /} に正規化）。 */
    private String rel(Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
