package ai.hack2026.web.util;

import java.util.Map;

/**
 * 観点コード(P-SEC等)の日本語名。日本のQA現場の慣習として、コードと日本語名を併記する
 * (指揮官指摘、2026-09-21)。定義の正は agent/perspectives.py の PERSPECTIVES(label)。
 * ここに追加したら、そちらとズレていないか確認すること。
 */
public final class PerspectiveLabels {

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("P-FUNC", "機能(正常系・異常系・境界値・同値分割)"),
            Map.entry("P-FLOW", "画面遷移・状態遷移"),
            Map.entry("P-INPUT", "入力検証"),
            Map.entry("P-TEXT", "表示・文言"),
            Map.entry("P-LINK", "リンク・画像"),
            Map.entry("P-UX", "ユーザビリティ"),
            Map.entry("P-A11Y", "アクセシビリティ"),
            Map.entry("P-SEC", "セキュリティ・堅牢性"),
            Map.entry("P-PERF", "表示速度"),
            Map.entry("P-RESP", "レスポンシブ")
    );

    private PerspectiveLabels() {
    }

    /** 例: "P-SEC セキュリティ・堅牢性"。未知のコードや空はコードのみ、nullは「—」。 */
    public static String withLabel(Object perspectiveCode) {
        if (perspectiveCode == null) {
            return "—";
        }
        String code = perspectiveCode.toString();
        if (code.isBlank()) {
            return "—";
        }
        String label = LABELS.get(code);
        return label == null ? code : code + " " + label;
    }
}
