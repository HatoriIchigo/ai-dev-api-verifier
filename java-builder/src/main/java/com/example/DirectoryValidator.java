package com.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Javaプロジェクトのディレクトリ構成を検証する。
 *
 * <p>検証ルール:
 * <ul>
 *   <li>{@code src/main/java/com/<projectName>/app/} 構成であること。</li>
 *   <li>{@code <projectName>} は {@code ^[0-9a-zA-Z-_]+$}（英数字・ハイフン・アンダースコア）に
 *       マッチすること。</li>
 *   <li>{@code src/main}・{@code src/test} の {@code java/} 配下は {@code .java} のみ、
 *       {@code resources/} 配下は {@code .yaml}／{@code .yml} のみ配置できること。</li>
 * </ul>
 */
public final class DirectoryValidator {

    /** プロジェクト名ディレクトリに許可される文字（ハイフンは末尾配置でリテラル扱い）。 */
    private static final Pattern PROJECT_NAME = Pattern.compile("^[0-9a-zA-Z_-]+$");

    private final Path root;
    private final Path openApiFile;

    /**
     * @param root 検証対象プロジェクトのルートディレクトリ
     */
    public DirectoryValidator(Path root) {
        this(root, null);
    }

    /**
     * @param root        検証対象プロジェクトのルートディレクトリ
     * @param openApiFile IF仕様書（OpenAPI）のパス。{@code null} の場合は OpenAPI 突合を行わない。
     */
    public DirectoryValidator(Path root, Path openApiFile) {
        this.root = root;
        this.openApiFile = openApiFile;
    }

    /**
     * 構成を検証し、違反メッセージの一覧を返す。
     *
     * @return 違反がなければ空リスト。1件以上あれば構成エラー。
     */
    public List<String> validate() throws IOException {
        List<String> violations = new ArrayList<>();

        // ファイル種別の配置ルール（main/test 共通）:
        //   src/<main|test>/java       配下は *.java のみ
        //   src/<main|test>/resources  配下は *.yaml / *.yml のみ
        violations.addAll(validateSourceTreeFileTypes());

        // 使用禁止語（dummy/mock/fake 等）の検査: src/main/java 全体が対象。
        violations.addAll(new ProhibitedWordValidator(root).validate());

        Path com = root.resolve("src").resolve("main").resolve("java").resolve("com");
        if (!Files.isDirectory(com)) {
            violations.add("必須ディレクトリが存在しません: src/main/java/com");
            return violations;
        }

        List<Path> projectDirs = new ArrayList<>();
        try (Stream<Path> children = Files.list(com)) {
            children.filter(Files::isDirectory).forEach(projectDirs::add);
        }

        List<Path> validProjects = new ArrayList<>();
        for (Path projectDir : projectDirs) {
            String name = projectDir.getFileName().toString();
            if (!PROJECT_NAME.matcher(name).matches()) {
                violations.add("プロジェクト名ディレクトリが命名規約 ^[0-9a-zA-Z-_]+$ に違反: com/" + name);
                continue;
            }
            Path app = projectDir.resolve("app");
            if (!Files.isDirectory(app)) {
                violations.add("app ディレクトリが存在しません: com/" + name + "/app");
                continue;
            }
            validProjects.add(app);

            // app配下の構成・コード内容を検証し、違反にプロジェクトパスを付与する。
            String prefix = "com/" + name + "/app: ";
            for (String violation : new AppStructureValidator(app).validate()) {
                violations.add(prefix + violation);
            }
            String basePackage = "com." + name + ".app";
            for (String violation : new CodeRuleValidator(app, basePackage).validate()) {
                violations.add(prefix + violation);
            }

            // IF仕様書（OpenAPI）が指定されていればゾーン整合を検証する。
            if (openApiFile != null) {
                for (String violation : new OpenApiValidator(app, openApiFile).validate()) {
                    violations.add(prefix + violation);
                }
            }
        }

        if (validProjects.isEmpty() && violations.isEmpty()) {
            violations.add("com 配下にプロジェクト名ディレクトリが存在しません（期待構成: com/<projectName>/app）");
        }

        return violations;
    }

    /**
     * {@code src/main} と {@code src/test} の {@code java/} 配下は {@code .java} のみ、
     * {@code resources/} 配下は {@code .yaml}／{@code .yml} のみであることを検証する。
     * 対象ディレクトリが存在しない場合はスキップする。
     */
    private List<String> validateSourceTreeFileTypes() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String stage : List.of("main", "test")) {
            Path src = root.resolve("src").resolve(stage);
            violations.addAll(checkOnlyExtensions(
                    src.resolve("java"), Set.of(".java"), ".java"));
            violations.addAll(checkOnlyExtensions(
                    src.resolve("resources"), Set.of(".yaml", ".yml"), ".yaml／.yml"));
        }
        return violations;
    }

    /** 指定ディレクトリ配下の全ファイルが {@code allowed} の拡張子のみであることを検証する。 */
    private List<String> checkOnlyExtensions(Path dir, Set<String> allowed, String label)
            throws IOException {
        List<String> violations = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return violations;
        }

        List<Path> files;
        try (Stream<Path> stream = Files.walk(dir)) {
            files = stream.filter(Files::isRegularFile).sorted().toList();
        }

        for (Path file : files) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            boolean ok = false;
            for (String ext : allowed) {
                if (name.endsWith(ext)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                violations.add(rel(file) + " は " + label + " のみ配置できます: "
                        + file.getFileName());
            }
        }
        return violations;
    }

    /** root からの相対パス（区切りを {@code /} に正規化）。 */
    private String rel(Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
