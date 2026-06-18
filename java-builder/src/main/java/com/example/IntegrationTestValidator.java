package com.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 統合テストの構成と、IF仕様書（OpenAPI）エンドポイントの網羅を検証する（テスト構成ルール 17・18）。
 *
 * <p>各プロジェクトは {@code src/test/java/com/<projectName>/integration/} に統合テストを持つこと。
 * <ul>
 *   <li>（17）{@code integration/} ディレクトリが存在し、{@code .java} ファイルが1つ以上あること。
 *       <b>常に検査</b>する（OpenAPI 未指定でも必須）。</li>
 *   <li>（18）IF仕様書（OpenAPI）が指定された場合、{@code paths} の各エンドポイント文字列
 *       （{@code /accounts/login} 等）が {@code integration/} 配下のいずれかの {@code .java} に
 *       リテラルとして現れること（ハードコード網羅）。OpenAPI 未指定時はこの網羅検査をスキップする。</li>
 * </ul>
 *
 * <p><b>既知の限界</b>: エンドポイントは OpenAPI の path 文字列を <em>そのまま部分一致</em>で照合する。
 * パスパラメータを含む path（例 {@code /users/{id}}）はテンプレートのまま照合するため、テスト側が
 * 具体値（{@code /users/123}）で記述している場合は網羅とみなされない。
 */
public final class IntegrationTestValidator {

    private final Path root;
    private final String projectName;
    private final List<String> endpointPaths;

    /**
     * @param root          検証対象プロジェクトのルート
     * @param projectName   {@code com/<projectName>} のプロジェクト名
     * @param endpointPaths OpenAPI の paths 一覧。空なら網羅検査（ルール18）はスキップする。
     */
    public IntegrationTestValidator(Path root, String projectName, List<String> endpointPaths) {
        this.root = root;
        this.projectName = projectName;
        this.endpointPaths = endpointPaths;
    }

    public List<String> validate() throws IOException {
        List<String> violations = new ArrayList<>();

        Path integrationDir = root.resolve("src").resolve("test").resolve("java")
                .resolve("com").resolve(projectName).resolve("integration");

        // ルール17: ディレクトリ存在
        if (!Files.isDirectory(integrationDir)) {
            violations.add("統合テストディレクトリが存在しません: " + rel(integrationDir));
            return violations;
        }

        // ルール17: .java ファイルが1つ以上
        List<Path> javaFiles;
        try (Stream<Path> stream = Files.walk(integrationDir)) {
            javaFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
        if (javaFiles.isEmpty()) {
            violations.add("統合テストディレクトリに .java ファイルがありません: " + rel(integrationDir));
            return violations;
        }

        // ルール18: OpenAPI 指定時のみ、全エンドポイントのハードコード網羅を検査
        if (endpointPaths.isEmpty()) {
            return violations;
        }

        StringBuilder corpus = new StringBuilder();
        for (Path file : javaFiles) {
            corpus.append(Files.readString(file)).append('\n');
        }
        String text = corpus.toString();

        for (String endpoint : endpointPaths) {
            if (!text.contains(endpoint)) {
                violations.add("OpenAPI のエンドポイント " + endpoint
                        + " が統合テストにハードコードされていません（" + rel(integrationDir)
                        + " 配下の .java にリテラルとして現れる必要があります）");
            }
        }
        return violations;
    }

    /** root からの相対パス（区切りを {@code /} に正規化）。 */
    private String rel(Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }
}
