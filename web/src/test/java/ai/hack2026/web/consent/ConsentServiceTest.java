package ai.hack2026.web.consent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** v0.6 P2(L-3): 同意の記録(ConsentService)。IPアドレスは生のまま保存せず、ハッシュだけを保持する。 */
@SpringBootTest
@ActiveProfiles("test")
class ConsentServiceTest {

    @Autowired
    private ConsentService consentService;

    @Test
    void termsConsentIsRecordedWithHashedIp() {
        Consent consent = consentService.recordTermsConsent(1L, 2L, "203.0.113.5");

        assertNotNull(consent.getId());
        assertEquals(Consent.TYPE_TERMS, consent.getConsentType());
        assertEquals(ConsentService.CURRENT_POLICY_VERSION, consent.getPolicyVersion());
        assertNotNull(consent.getIpHash());
        assertNotEquals("203.0.113.5", consent.getIpHash()); // 生のIPそのものは保存しない
        assertNull(consent.getTargetHostname());
    }

    @Test
    void perExecutionConsentRecordsTargetHostname() {
        Consent consent = consentService.recordPerExecutionConsent(1L, 2L, "127.0.0.1", "203.0.113.5");

        assertEquals(Consent.TYPE_PER_EXECUTION, consent.getConsentType());
        assertEquals("127.0.0.1", consent.getTargetHostname());
    }

    @Test
    void sameIpAlwaysHashesToSameValue() {
        Consent a = consentService.recordTermsConsent(1L, 2L, "203.0.113.5");
        Consent b = consentService.recordTermsConsent(3L, 4L, "203.0.113.5");

        assertEquals(a.getIpHash(), b.getIpHash());
    }

    @Test
    void nullIpProducesNullHashWithoutError() {
        Consent consent = consentService.recordTermsConsent(1L, 2L, null);

        assertNull(consent.getIpHash());
    }
}
