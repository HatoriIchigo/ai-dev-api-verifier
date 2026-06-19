package com.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code app/} ディレクトリ配下の構成を検証する。
 *
 * <p>許可される {@code .java} の配置:
 * <ul>
 *   <li>{@code app/Application.java}（app直下はこのファイルのみ許可）</li>
 *   <li>{@code app/top/*.java}（最上位層・外部接続ありエントリ）</li>
 *   <li>{@code app/internal/*.java}（backend完結エントリ・外部接続なし）</li>
 *   <li>{@code app/layer<数値>/*.java}（サービス層、複数可。番号は1始まりの連番であること）</li>
 *   <li>{@code app/repository/*.java}（外部ツール連携）</li>
 *   <li>{@code app/dto/in/*.java}（in側DTO）</li>
 *   <li>{@code app/dto/out/*.java}（out側DTO）</li>
 *   <li>{@code app/log/*.java}（ログ）</li>
 *   <li>{@code app/util/*.java}（util）</li>
 *   <li>{@code app/validation/*.java}（バリデーション）</li>
 *   <li>{@code app/constants/*.java}（定数）</li>
 * </ul>
 *
 * <p>追加ルール:
 * <ul>
 *   <li>上記以外の場所に {@code .java} があればエラー。</li>
 *   <li>{@code dto/in} と {@code dto/out} の {@code .java} ファイル数が一致しなければエラー。</li>
 * </ul>
 */
public final class AppStructureValidator {

    /** *.java を直接保持できる単純ディレクトリ（app直下からの相対パス）。 */
    private static final Set<String> ALLOWED_LEAF_DIRS = Set.of(
            "top", "internal", "repository", "log", "util", "validation", "constants");

    /** サービス層ディレクトリ: layer + 数値（多桁可）。 */
    private static final Pattern LAYER_DIR = Pattern.compile("^layer[0-9]+$");

    private static final String DTO_IN = "dto/in";
    private static final String DTO_OUT = "dto/out";
    private static final String REPOSITORY = "repository";

    private final Path appDir;

    public AppStructureValidator(Path appDir) {
        this.appDir = appDir;
    }

    public List<String> validate() throws IOException {
        List<String> violations = new ArrayList<>();
        int inCount = 0;
        int outCount = 0;

        List<Path> javaFiles;
        try (Stream<Path> stream = Files.walk(appDir)) {
            javaFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
        }

        for (Path file : javaFiles) {
            Path rel = appDir.relativize(file);
            Path parent = rel.getParent();
            String parentDir = parent == null ? "" : parent.toString().replace('\\', '/');
            String fileName = file.getFileName().toString();

            if (parentDir.isEmpty()) {
                if (!fileName.equals("Application.java")) {
                    violations.add("app直下に許可されないJavaファイル: " + fileName
                            + "（app直下は Application.java のみ許可）");
                }
            } else if (DTO_IN.equals(parentDir)) {
                inCount++;
                violations.addAll(checkSuffix(parentDir, fileName, "InDto"));
            } else if (DTO_OUT.equals(parentDir)) {
                outCount++;
                violations.addAll(checkSuffix(parentDir, fileName, "OutDto"));
            } else if (REPOSITORY.equals(parentDir)) {
                violations.addAll(checkSuffix(parentDir, fileName, "Repository"));
            } else if (!isAllowedDir(parentDir)) {
                violations.add("許可されない場所のJavaファイル: " + parentDir + "/" + fileName);
            }
        }

        if (inCount != outCount) {
            violations.add("dto/in と dto/out のファイル数が一致しません: in=" + inCount + ", out=" + outCount);
        }

        violations.addAll(validateLayerSequence());

        return violations;
    }

    /**
     * サービス層ディレクトリ（{@code layer<数値>}）の番号が1始まりの連番であることを検証する。
     * 歯抜け（例: layer1〜4, layer6〜7 で layer5 が欠番）があればエラー。
     */
    private List<String> validateLayerSequence() throws IOException {
        List<String> violations = new ArrayList<>();

        TreeSet<Integer> numbers = new TreeSet<>();
        try (Stream<Path> children = Files.list(appDir)) {
            children.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> LAYER_DIR.matcher(name).matches())
                    .forEach(name -> numbers.add(Integer.parseInt(name.substring("layer".length()))));
        }

        if (numbers.isEmpty()) {
            return violations;
        }

        List<Integer> sorted = new ArrayList<>(numbers);
        for (int i = 0; i < sorted.size(); i++) {
            int expected = i + 1;
            int actual = sorted.get(i);
            if (actual != expected) {
                violations.add("サービス層の番号が歯抜けです（layer" + expected + " が存在しません）。layer"
                        + actual + " 以降を降格してください。");
                break;
            }
        }

        return violations;
    }

    private static boolean isAllowedDir(String parentDir) {
        return ALLOWED_LEAF_DIRS.contains(parentDir) || LAYER_DIR.matcher(parentDir).matches();
    }

    /**
     * ルールD5: ゾーン別の命名サフィックスを検証する。
     * クラス名（ファイルのベース名）が指定サフィックスで終わらなければエラー。
     * repository とその in/out DTO の単純名衝突（Java の single-type import 衝突）を防ぐための一意化。
     */
    private static List<String> checkSuffix(String parentDir, String fileName, String suffix) {
        String base = fileName.substring(0, fileName.length() - ".java".length());
        if (base.endsWith(suffix) && base.length() > suffix.length()) {
            return List.of();
        }
        return List.of(parentDir + "/" + fileName + " は命名サフィックス \"" + suffix
                + "\" で終わる必要があります（例: <ステム>" + suffix + ".java）");
    }
}
