package ai.hack2026.web.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** v0.7 P6中核(状態管理・抑制): Findingの「同じ不具合」を、実行をまたいで識別するための指紋。
 * FindingのidはRunごとに振り直される(agent/loop.py RunState.add_finding)ため使えない。
 * agent/loop.pyの重複統合(_dedupe_findings/_broad_dedupe_key)と同じ考え方を採用する:
 * 仕様参照(specRef)があれば「観点・種別・正規化した題名・仕様参照」、無ければ
 * 「観点・種別・詳細」を基にする。ワーカー側のFinding契約は変えず、Java側だけで計算する。 */
public final class FindingFingerprint {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private FindingFingerprint() {
    }

    public static String compute(Map<String, Object> finding) {
        String perspective = str(finding.get("perspective"));
        String kind = str(finding.get("kind"));
        List<String> specRef = stringList(finding.get("specRef"));
        String basis;
        if (!specRef.isEmpty()) {
            List<String> sorted = new ArrayList<>(specRef);
            Collections.sort(sorted);
            basis = perspective + "|" + kind + "|" + normalizeTitle(str(finding.get("title"))) + "|" + String.join(",", sorted);
        } else {
            basis = perspective + "|" + kind + "|" + str(finding.get("detail"));
        }
        return sha256Hex(basis);
    }

    private static String normalizeTitle(String title) {
        return WHITESPACE.matcher(title.strip().toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256が使えません", e);
        }
    }
}
