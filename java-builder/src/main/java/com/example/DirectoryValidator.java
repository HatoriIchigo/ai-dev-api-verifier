package com.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Java プロジェクトの**コード内容（AST 等）検査**をオーケストレーションする。
 *
 * <p><b>ディレクトリ／ファイル構成の検証は {@code directory-checker} に移行済み</b>（旧
 * {@code AppStructureValidator} および本クラスが行っていた構成検証は削除）。本クラスは
 * {@code <src>/main/java/com/<projectName>/app} を走査し、各種コード内容バリデータを駆動する役割に縮小した。
 *
 * <p>構成が不正（{@code com} や {@code app} が無い、プロジェクト名が不正）な場合は、構成エラーとしては
 * 報告せず**静かにスキップ**する（構成の正否は {@code directory-checker} が判定するため、二重化しない）。
 *
 * <p>実行する検査:
 * <ul>
 *   <li>{@link ProhibitedWordValidator}（使用禁止語・main 全体）</li>
 *   <li>{@link LocalhostValidator}（localhost 禁止・main/test 全体）</li>
 *   <li>{@link ConstantsTestDataValidator}（constants 値のテスト重複・main/test）</li>
 *   <li>{@link CodeRuleValidator}（AST によるコード内容ルール・レイヤー依存・外部連携。app 単位）</li>
 *   <li>{@link OpenApiValidator}（IF 仕様書とのゾーン整合。app 単位・OpenAPI 指定時）</li>
 *   <li>{@link IntegrationTestValidator}（ルール18 エンドポイント網羅。OpenAPI 指定時）</li>
 * </ul>
 */
public final class DirectoryValidator {

    /** basePackage を構成できるプロジェクト名（不正名は静かにスキップ）。命名検証は directory-checker が担う。 */
    private static final Pattern PROJECT_NAME = Pattern.compile("^[0-9a-zA-Z_-]+$");

    private final Path root;
    private final Path srcRoot;
    private final Path openApiFile;

    /**
     * @param root 検証対象プロジェクトのルートディレクトリ
     */
    public DirectoryValidator(Path root) {
        this(root, root.resolve(BuildConfig.DEFAULT_SRC_ROOT), null);
    }

    /**
     * @param root        検証対象プロジェクトのルートディレクトリ
     * @param openApiFile IF仕様書（OpenAPI）のパス。{@code null} の場合は OpenAPI 突合を行わない。
     */
    public DirectoryValidator(Path root, Path openApiFile) {
        this(root, root.resolve(BuildConfig.DEFAULT_SRC_ROOT), openApiFile);
    }

    /**
     * @param root        検証対象プロジェクトのルートディレクトリ
     * @param srcRoot     ソースルート（{@code root} 配下。従来の固定 {@code src} に相当）。
     * @param openApiFile IF仕様書（OpenAPI）のパス。{@code null} の場合は OpenAPI 突合を行わない。
     */
    public DirectoryValidator(Path root, Path srcRoot, Path openApiFile) {
        this.root = root;
        this.srcRoot = srcRoot;
        this.openApiFile = openApiFile;
    }

    /**
     * コード内容検査を実行し、違反メッセージの一覧を返す。
     *
     * @return 違反がなければ空リスト。
     */
    public List<String> validate() throws IOException {
        List<String> violations = new ArrayList<>();

        // 使用禁止語（dummy/mock/fake 等）の検査: src/main/java 全体が対象。
        violations.addAll(new ProhibitedWordValidator(root, srcRoot).validate());

        // ルール1.4: localhost のハードコード禁止（src/main/java・src/test/java 双方が対象）。
        violations.addAll(new LocalhostValidator(root, srcRoot).validate());

        // ルール19: constants の定数値とテストリテラルの重複検出（答え合わせ漏洩。src/main・src/test 双方が対象）。
        violations.addAll(new ConstantsTestDataValidator(root, srcRoot).validate());

        // ルール18: IF仕様書（OpenAPI）が指定されていれば、エンドポイント網羅検査に使う path 一覧を読み込む。
        List<String> endpointPaths = openApiFile != null
                ? OpenApiValidator.loadEndpointPaths(openApiFile)
                : List.of();

        Path com = srcRoot.resolve("main").resolve("java").resolve("com");
        if (!Files.isDirectory(com)) {
            // 構成不正（com 不在）は directory-checker が報告する。ここでは内容検査の対象が無いだけ。
            return violations;
        }

        List<Path> projectDirs = new ArrayList<>();
        try (Stream<Path> children = Files.list(com)) {
            children.filter(Files::isDirectory).forEach(projectDirs::add);
        }

        for (Path projectDir : projectDirs) {
            String name = projectDir.getFileName().toString();
            // 不正なプロジェクト名は basePackage を構成できないためスキップ（命名検証は directory-checker）。
            if (!PROJECT_NAME.matcher(name).matches()) {
                continue;
            }
            Path app = projectDir.resolve("app");
            if (!Files.isDirectory(app)) {
                // app 不在は構成不正（directory-checker が報告）。内容検査の対象が無いためスキップ。
                continue;
            }

            // app 配下のコード内容を検査し、違反にプロジェクトパスを付与する。
            String prefix = "com/" + name + "/app: ";
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

            // ルール18: エンドポイント網羅（OpenAPI 指定時のみ。ルール17の構造検証は directory-checker に移行）。
            violations.addAll(new IntegrationTestValidator(root, srcRoot, name, endpointPaths).validate());
        }

        return violations;
    }
}
