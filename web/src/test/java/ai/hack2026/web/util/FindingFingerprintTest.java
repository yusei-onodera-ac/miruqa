package ai.hack2026.web.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** v0.7 P6中核(状態管理・抑制)。agent/loop.pyの_broad_dedupe_key()と同じ考え方の
 * fingerprintが、Java側でも一貫して計算できることを確認する。 */
class FindingFingerprintTest {

    private static Map<String, Object> finding(String perspective, String kind, String title, String detail, List<String> specRef) {
        Map<String, Object> f = new java.util.HashMap<>();
        f.put("perspective", perspective);
        f.put("kind", kind);
        f.put("title", title);
        f.put("detail", detail);
        f.put("specRef", specRef);
        return f;
    }

    @Test
    void sameSpecRefAndTitleProduceSameFingerprintDespiteDifferentDetail() {
        String a = FindingFingerprint.compute(finding("P-TEXT", "deviation", "送料表示が違う", "送料800円のはずが無料", List.of("SPEC-012")));
        String b = FindingFingerprint.compute(finding("P-TEXT", "deviation", "送料表示が違う", "送料800円のはずが0円", List.of("SPEC-012")));
        assertEquals(a, b);
    }

    @Test
    void differentSpecRefProducesDifferentFingerprint() {
        String a = FindingFingerprint.compute(finding("P-TEXT", "deviation", "t", "d", List.of("SPEC-012")));
        String b = FindingFingerprint.compute(finding("P-TEXT", "deviation", "t", "d", List.of("SPEC-099")));
        assertNotEquals(a, b);
    }

    @Test
    void withoutSpecRefFallsBackToDetailAndDiffersWhenDetailDiffers() {
        String a = FindingFingerprint.compute(finding("P-SEC", "robustness", "t", "detail A", List.of()));
        String b = FindingFingerprint.compute(finding("P-SEC", "robustness", "t", "detail B", List.of()));
        assertNotEquals(a, b);
    }

    @Test
    void withoutSpecRefSameDetailProducesSameFingerprint() {
        String a = FindingFingerprint.compute(finding("P-SEC", "robustness", "t1", "same detail", List.of()));
        String b = FindingFingerprint.compute(finding("P-SEC", "robustness", "t2", "same detail", List.of()));
        assertEquals(a, b);
    }

    @Test
    void whitespaceOnlyTitleDifferenceStillMatches() {
        String a = FindingFingerprint.compute(finding("P-LINK", "unreachable", "送料 の表示", "d", List.of("SPEC-001")));
        String b = FindingFingerprint.compute(finding("P-LINK", "unreachable", "送料の表示", "d", List.of("SPEC-001")));
        assertEquals(a, b);
    }

    @Test
    void specRefOrderDoesNotMatter() {
        String a = FindingFingerprint.compute(finding("P-LINK", "unreachable", "t", "d", List.of("SPEC-001", "SPEC-002")));
        String b = FindingFingerprint.compute(finding("P-LINK", "unreachable", "t", "d", List.of("SPEC-002", "SPEC-001")));
        assertEquals(a, b);
    }
}
