package com.example;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * java-builder 自身の実行設定を解決する。
 *
 * <p>従来は検証対象のソースルートを固定で {@code src} として扱っていたが、案件によってソースルート名が
 * 異なる（{@code source}・モジュール配下など）ため、固定値ではなく環境変数または application.yaml から
 * 解決できるようにする。可変にするのは「検証対象ルート（第1引数）からソースルートまでの相対パス」のみで、
 * 配下の {@code main}/{@code test} および {@code java}/{@code resources} 構造は従来どおり固定とする。
 *
 * <p>解決の優先順位:
 * <ol>
 *   <li>環境変数 {@value #ENV_SRC_ROOT}（Docker 実行時は {@code -e} で注入する）</li>
 *   <li>application.yaml の {@value #YAML_KEY} キー。設定ファイルの場所は
 *       {@code -Dapplication.config=<path>} で指定でき、未指定時はカレントディレクトリの
 *       {@code application.yaml}／{@code application.yml} を探す</li>
 *   <li>既定値 {@value #DEFAULT_SRC_ROOT}（従来の固定値と同じ。後方互換）</li>
 * </ol>
 *
 * <p>設定値は検証対象ルートからの相対パスとして解釈する（例: {@code src}、{@code source}、
 * {@code modules/app/src}）。絶対パスや {@code ..} を含む値は与えないこと（与えても
 * {@code root.resolve(..).normalize()} で正規化されるが、ルート外を指すと検証は空振りする）。
 */
public final class BuildConfig {

    /** ソースルートの既定（検証対象ルートからの相対パス）。従来の固定値と同じ。 */
    public static final String DEFAULT_SRC_ROOT = "src";

    private static final String ENV_SRC_ROOT = "JAVA_BUILDER_SRC_ROOT";
    private static final String CONFIG_PROPERTY = "application.config";
    private static final String YAML_KEY = "src-root";
    private static final List<String> DEFAULT_CONFIG_FILES =
            List.of("application.yaml", "application.yml");

    private BuildConfig() {
    }

    /**
     * 検証対象ルート {@code root} 配下のソースルート（絶対パス）を解決する。
     *
     * @param root 検証対象プロジェクトのルート
     * @return {@code root} 配下のソースルート（既定は {@code root/src}）
     */
    public static Path resolveSrcRoot(Path root) {
        return root.resolve(srcRootRelative()).normalize();
    }

    /**
     * ソースルートの相対パス文字列を解決する（既定 {@value #DEFAULT_SRC_ROOT}）。
     * 優先順位はクラス Javadoc を参照。
     */
    public static String srcRootRelative() {
        String env = System.getenv(ENV_SRC_ROOT);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        String fromYaml = fromApplicationYaml();
        if (fromYaml != null && !fromYaml.isBlank()) {
            return fromYaml.trim();
        }
        return DEFAULT_SRC_ROOT;
    }

    /**
     * application.yaml から {@value #YAML_KEY} を読む。ファイルが無い／キーが無い／解析に失敗した場合は
     * {@code null} を返し、上位で既定値にフォールバックさせる（設定不備で検証自体を止めない方針）。
     */
    @SuppressWarnings("unchecked")
    private static String fromApplicationYaml() {
        for (Path config : configCandidates()) {
            if (!Files.isRegularFile(config)) {
                continue;
            }
            try (InputStream in = Files.newInputStream(config)) {
                Object loaded = new Yaml().load(in);
                if (loaded instanceof Map) {
                    Object value = ((Map<String, Object>) loaded).get(YAML_KEY);
                    if (value != null) {
                        return value.toString();
                    }
                }
            } catch (IOException | RuntimeException e) {
                // 読み込み・解析失敗は次の候補／既定へフォールバック（SnakeYAML は実行時例外を投げ得る）。
                return null;
            }
            // ファイルは存在したがキーが無い → 既定へフォールバック。
            return null;
        }
        return null;
    }

    /** 探索する application.yaml の候補一覧。{@code -Dapplication.config} 指定時はそれだけを対象とする。 */
    private static List<Path> configCandidates() {
        String override = System.getProperty(CONFIG_PROPERTY);
        if (override != null && !override.isBlank()) {
            return List.of(Path.of(override.trim()));
        }
        List<Path> candidates = new ArrayList<>();
        for (String name : DEFAULT_CONFIG_FILES) {
            candidates.add(Path.of(name));
        }
        return candidates;
    }
}
