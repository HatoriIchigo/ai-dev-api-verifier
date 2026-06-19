package com.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * IF仕様書（OpenAPI）エンドポイントの統合テスト網羅を検証する（テスト構成ルール18）。
 *
 * <p>{@code paths} の各エンドポイント文字列（{@code /accounts/login} 等）が、
 * {@code <src>/test/java/com/<projectName>/integration/} 配下のいずれかの {@code .java} に
 * リテラルとして現れること（ハードコード網羅）を検査する。OpenAPI 未指定（{@code endpointPaths} が空）時は
 * 何も検査しない。
 *
 * <p><b>役割分担</b>: 統合テストディレクトリの<b>存在</b>と {@code .java} の<b>有無</b>（旧ルール17の構造検証）は
 * {@code directory-checker} に移行済み。本クラスは OpenAPI を要するルール18（エンドポイント網羅）のみを担う。
 * そのため integration ディレクトリが存在しない場合は corpus を空として扱い、各エンドポイントを「未網羅」と報告する。
 *
 * <p><b>既知の限界</b>: エンドポイントは OpenAPI の path 文字列を <em>そのまま部分一致</em>で照合する。
 * パスパラメータを含む path（例 {@code /users/{id}}）はテンプレートのまま照合するため、テスト側が
 * 具体値（{@code /users/123}）で記述している場合は網羅とみなされない。
 */
public final class IntegrationTestValidator {

    private final Path root;
    private final Path srcRoot;
    private final String projectName;
    private final List<String> endpointPaths;

    /**
     * @param root          検証対象プロジェクトのルート
     * @param projectName   {@code com/<projectName>} のプロジェクト名
     * @param endpointPaths OpenAPI の paths 一覧。空なら網羅検査（ルール18）はスキップする。
     */
    public IntegrationTestValidator(Path root, String projectName, List<String> endpointPaths) {
        this(root, root.resolve(BuildConfig.DEFAULT_SRC_ROOT), projectName, endpointPaths);
    }

    /**
     * @param root          検証対象プロジェクトのルート（違反メッセージの相対パス表示に使用）
     * @param srcRoot       ソースルート（{@code root} 配下。従来の固定 {@code src} に相当）
     * @param projectName   {@code com/<projectName>} のプロジェクト名
     * @param endpointPaths OpenAPI の paths 一覧。空なら網羅検査（ルール18）はスキップする。
     */
    public IntegrationTestValidator(Path root, Path srcRoot, String projectName,
                                    List<String> endpointPaths) {
        this.root = root;
        this.srcRoot = srcRoot;
        this.projectName = projectName;
        this.endpointPaths = endpointPaths;
    }

    public List<String> validate() throws IOException {
        List<String> violations = new ArrayList<>();

        // ルール18のみ。OpenAPI 未指定なら検査対象なし（ルール17の構造検証は directory-checker に移行）。
        if (endpointPaths.isEmpty()) {
            return violations;
        }

        Path integrationDir = srcRoot.resolve("test").resolve("java")
                .resolve("com").resolve(projectName).resolve("integration");

        // integration 配下の全 .java を corpus 化（ディレクトリが無ければ空 corpus = 全エンドポイント未網羅）。
        StringBuilder corpus = new StringBuilder();
        if (Files.isDirectory(integrationDir)) {
            List<Path> javaFiles;
            try (Stream<Path> stream = Files.walk(integrationDir)) {
                javaFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .toList();
            }
            for (Path file : javaFiles) {
                corpus.append(Files.readString(file)).append('\n');
            }
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
