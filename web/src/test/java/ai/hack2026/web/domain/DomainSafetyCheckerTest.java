package ai.hack2026.web.domain;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.6 P2(L-1/S-2): ドメイン確認先へのSSRF対策(DomainSafetyChecker)。
 * リテラルIPはDNSを介さずInetAddress.getByNameで解釈できるため、実際の名前解決なしにテストできる。 */
class DomainSafetyCheckerTest {

    private final DomainSafetyChecker checker = new DomainSafetyChecker("127.0.0.1:8765");

    private InetAddress[] literal(String ip) throws UnknownHostException {
        return new InetAddress[]{InetAddress.getByName(ip)};
    }

    @Test
    void loopbackAddressIsBlocked() throws UnknownHostException {
        Optional<String> reason = checker.blockedReason(literal("127.0.0.1"));
        assertTrue(reason.isPresent());
    }

    @Test
    void privateAddressIsBlocked() throws UnknownHostException {
        assertTrue(checker.blockedReason(literal("10.0.0.5")).isPresent());
        assertTrue(checker.blockedReason(literal("192.168.1.1")).isPresent());
    }

    @Test
    void cloudMetadataAddressIsBlocked() throws UnknownHostException {
        Optional<String> reason = checker.blockedReason(literal("169.254.169.254"));
        assertTrue(reason.isPresent());
    }

    @Test
    void publicAddressIsAllowed() throws UnknownHostException {
        assertFalse(checker.blockedReason(literal("93.184.216.34")).isPresent());
    }

    @Test
    void sandboxHostSkipsResolutionAndIsSafe() {
        // サンドボックス例外のホストは、実際に名前解決しなくても安全と判定される
        Optional<String> reason = checker.checkHostnameSafe("127.0.0.1:8765");
        assertFalse(reason.isPresent());
    }

    @Test
    void nonSandboxLoopbackHostIsBlocked() {
        // 127.0.0.1:9999はsandbox-hosts("127.0.0.1:8765")に含まれないため、実際に名前解決され、
        // ループバックとして遮断される
        Optional<String> reason = checker.checkHostnameSafe("127.0.0.1:9999");
        assertTrue(reason.isPresent());
    }
}
