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
 * IF仕様書（OpenAPI形式）とコード実態のゾーン整合を検証する（declare + verify）。
 *
 * <p>operation レベルのベンダ拡張 {@code x-internal} で外部接続要否を宣言する。
 * <ul>
 *   <li>{@code x-internal: true} … 外部接続なし。エントリクラスは {@code app/internal/} に置く。</li>
 *   <li>{@code x-internal} 省略／{@code false} … 外部接続あり（既定）。エントリクラスは {@code app/top/}。</li>
 * </ul>
 *
 * <p>{@code operationId} をエントリクラス名（PascalCase）にマッピングし、宣言どおりのゾーンに
 * クラスが存在すること（かつ逆ゾーンに存在しないこと）を検証する。
 * 例: {@code checkHealth -> internal/CheckHealth.java}、{@code loginAccount -> top/LoginAccount.java}。
 *
 * <p>internal エントリの「外部到達禁止」は {@link CodeRuleValidator} のルール10で別途検証する。
 */
public final class OpenApiValidator {

    private static final String INTERNAL_DIR = "internal";
    private static final String TOP_DIR = "top";

    private final Path appDir;
    private final Path openApiFile;

    public OpenApiValidator(Path appDir, Path openApiFile) {
        this.appDir = appDir;
        this.openApiFile = openApiFile;
    }

    @SuppressWarnings("unchecked")
    public List<String> validate() throws IOException {
        List<String> violations = new ArrayList<>();

        Object loaded;
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(openApiFile)) {
            loaded = yaml.load(in);
        }
        if (!(loaded instanceof Map)) {
            violations.add("IF仕様書を解析できません: " + openApiFile);
            return violations;
        }

        Object pathsObj = ((Map<String, Object>) loaded).get("paths");
        if (!(pathsObj instanceof Map)) {
            // paths が無ければ検証対象なし
            return violations;
        }

        Map<String, Object> paths = (Map<String, Object>) pathsObj;
        for (Object pathItemObj : paths.values()) {
            if (!(pathItemObj instanceof Map)) {
                continue;
            }
            Map<String, Object> pathItem = (Map<String, Object>) pathItemObj;
            for (Object operationObj : pathItem.values()) {
                if (!(operationObj instanceof Map)) {
                    continue;
                }
                Map<String, Object> operation = (Map<String, Object>) operationObj;
                Object opIdObj = operation.get("operationId");
                if (!(opIdObj instanceof String) || ((String) opIdObj).isEmpty()) {
                    // operationId を持つもののみエントリとして扱う
                    continue;
                }
                String operationId = (String) opIdObj;
                boolean internal = Boolean.TRUE.equals(operation.get("x-internal"));
                violations.addAll(verifyZone(operationId, internal));
            }
        }
        return violations;
    }

    /** 宣言ゾーンにエントリクラスが存在し、逆ゾーンには存在しないことを検証する。 */
    private List<String> verifyZone(String operationId, boolean internal) {
        List<String> violations = new ArrayList<>();
        String className = pascalCase(operationId);
        String expectedDir = internal ? INTERNAL_DIR : TOP_DIR;
        String otherDir = internal ? TOP_DIR : INTERNAL_DIR;
        String zoneLabel = internal ? "x-internal:true（外部接続なし）" : "外部接続あり（既定）";

        Path expected = appDir.resolve(expectedDir).resolve(className + ".java");
        Path other = appDir.resolve(otherDir).resolve(className + ".java");

        if (!Files.isRegularFile(expected)) {
            violations.add("operationId " + operationId + " は " + zoneLabel + " のため "
                    + expectedDir + "/" + className + ".java が必要ですが存在しません");
        }
        if (Files.isRegularFile(other)) {
            violations.add("operationId " + operationId + " は " + zoneLabel + " のため "
                    + expectedDir + " に置くべきですが " + otherDir + "/" + className + ".java が存在します");
        }
        return violations;
    }

    /** camelCase の operationId を PascalCase のクラス名に変換する（先頭を大文字化）。 */
    private String pascalCase(String operationId) {
        return Character.toUpperCase(operationId.charAt(0)) + operationId.substring(1);
    }
}
