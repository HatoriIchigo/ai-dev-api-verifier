package com.example;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class Main {

    public static void main(String[] args) {
        // 第1引数で検証対象ルートを指定。省略時はカレントディレクトリ。
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();

        // 第2引数で IF仕様書（OpenAPI）パスを指定。省略時は OpenAPI 突合をスキップ。
        Path openApi = args.length > 1
                ? Path.of(args[1]).toAbsolutePath().normalize()
                : null;

        // ソースルートは固定の "src" ではなく環境変数／application.yaml から解決する（既定 "src"）。
        Path srcRoot = BuildConfig.resolveSrcRoot(root);

        DirectoryValidator validator = new DirectoryValidator(root, srcRoot, openApi);
        List<String> violations;
        try {
            violations = validator.validate();
        } catch (IOException e) {
            System.err.println("検証中にエラーが発生しました: " + e.getMessage());
            System.exit(2);
            return;
        }

        if (!violations.isEmpty()) {
            System.err.println("コード内容チェックエラー (" + root + "):");
            for (String violation : violations) {
                System.err.println("  - " + violation);
            }
            System.exit(1);
        }

        System.out.println("コード内容チェック: OK");
    }
}
