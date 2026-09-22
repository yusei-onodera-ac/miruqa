package ai.hack2026.web.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.6 P2(L-4、指揮官指摘 2026-09-21): 審査員・協賛企業・政府機関等のホスト名の拒否リスト。 */
class HostnameDenylistTest {

    private final HostnameDenylist denylist = new HostnameDenylist(".go.jp,.lg.jp", "cyberace.co.jp,orcarouter.ai");

    @Test
    void governmentSuffixIsDenied() {
        assertTrue(denylist.deniedReason("www.city.example.lg.jp").isPresent());
        assertTrue(denylist.deniedReason("example.go.jp").isPresent());
    }

    @Test
    void sponsorHostnameIsDenied() {
        assertTrue(denylist.deniedReason("cyberace.co.jp").isPresent());
    }

    @Test
    void sponsorSubdomainIsDenied() {
        assertTrue(denylist.deniedReason("www.orcarouter.ai").isPresent());
    }

    @Test
    void unrelatedHostnameIsNotDenied() {
        Optional<String> reason = denylist.deniedReason("127.0.0.1");
        assertFalse(reason.isPresent());
    }

    @Test
    void caseInsensitiveMatch() {
        assertTrue(denylist.deniedReason("CyberAce.CO.JP").isPresent());
    }
}
