package com.example;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@code src/main/java} 配下（本番コード全体）に使用禁止語が含まれていないか検証する。
 *
 * <p>テストダブル／仮実装（{@code dummy}・{@code mock}・{@code fake}・{@code stub} 等）の本番混入を
 * 検出する。判定は大文字小文字を無視した部分一致で、クラス名・メソッド名・変数名・文字列・コメントなど
 * ソーステキスト全体が対象。{@code src/test} は対象外。
 *
 * <p>禁止語は外部設定ファイルから読み込む。優先順位:
 * システムプロパティ {@code prohibited.words} のパス → カレントディレクトリの
 * {@value #PROHIBITED_WORDS_FILE} → JAR同梱リソース。
 */
public final class ProhibitedWordValidator {

    private static final String PROHIBITED_WORDS_FILE = "prohibited-words.txt";

    private final Path root;
    private final Path srcRoot;
    private final List<String> prohibitedWords;

    public ProhibitedWordValidator(Path root) {
        this(root, root.resolve(BuildConfig.DEFAULT_SRC_ROOT));
    }

    /**
     * @param root    検証対象プロジェクトのルート（違反メッセージの相対パス表示に使用）
     * @param srcRoot ソースルート（{@code root} 配下。従来の固定 {@code src} に相当）
     */
    public ProhibitedWordValidator(Path root, Path srcRoot) {
        this.root = root;
        this.srcRoot = srcRoot;
        this.prohibitedWords = loadProhibitedWords();
    }

    public List<String> validate() throws IOException {
        List<String> violations = new ArrayList<>();
        if (prohibitedWords.isEmpty()) {
            return violations;
        }

        Path mainJava = srcRoot.resolve("main").resolve("java");
        if (!Files.isDirectory(mainJava)) {
            return violations;
        }

        List<Path> javaFiles;
        try (Stream<Path> stream = Files.walk(mainJava)) {
            javaFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }

        for (Path file : javaFiles) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String lower = lines.get(i).toLowerCase(Locale.ROOT);
                for (String word : prohibitedWords) {
                    if (lower.contains(word)) {
                        violations.add(rel(file) + ":" + (i + 1)
                                + " main/java では使用禁止語（dummy/mock/fake 等）は使用できません: " + word);
                    }
                }
            }
        }
        return violations;
    }

    private String rel(Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /** 禁止語を読み込む（大文字小文字無視の比較用に小文字化）。 */
    private static List<String> loadProhibitedWords() {
        List<String> lines = null;

        String override = System.getProperty("prohibited.words");
        try {
            if (override != null) {
                lines = Files.readAllLines(Path.of(override));
            } else {
                Path local = Path.of(PROHIBITED_WORDS_FILE);
                if (Files.isRegularFile(local)) {
                    lines = Files.readAllLines(local);
                }
            }
        } catch (IOException e) {
            lines = null;
        }

        if (lines == null) {
            try (InputStream in =
                         ProhibitedWordValidator.class.getResourceAsStream("/" + PROHIBITED_WORDS_FILE)) {
                if (in != null) {
                    lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                            .lines().toList();
                }
            } catch (IOException e) {
                lines = null;
            }
        }

        List<String> words = new ArrayList<>();
        if (lines != null) {
            for (String line : lines) {
                String text = line;
                int hash = text.indexOf('#');
                if (hash >= 0) {
                    text = text.substring(0, hash);
                }
                text = text.trim();
                if (!text.isEmpty()) {
                    words.add(text.toLowerCase(Locale.ROOT));
                }
            }
        }
        return words;
    }
}
