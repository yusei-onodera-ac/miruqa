package ai.hack2026.web.consent;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** v0.6 P2(L-3): 同意の記録。利用規約・プライバシーポリシー(下書き。【要法務確認】)への同意と、
 * 実行ごとの「このドメインをテストする権限がある」旨の同意を記録する。
 * IPアドレスは生のままでは保存せず、SHA-256ハッシュだけを保持する(個人情報の最小化)。 */
@Service
public class ConsentService {

    // 規約類は下書き段階のため、版番号もその旨を示す(【要法務確認】)
    public static final String CURRENT_POLICY_VERSION = "v1-draft";

    private final ConsentRepository consentRepository;

    public ConsentService(ConsentRepository consentRepository) {
        this.consentRepository = consentRepository;
    }

    public Consent recordTermsConsent(Long userId, Long organizationId, String ipAddress) {
        return record(userId, organizationId, Consent.TYPE_TERMS, null, ipAddress);
    }

    public Consent recordPerExecutionConsent(Long userId, Long organizationId, String targetHostname, String ipAddress) {
        return record(userId, organizationId, Consent.TYPE_PER_EXECUTION, targetHostname, ipAddress);
    }

    private Consent record(Long userId, Long organizationId, String type, String targetHostname, String ipAddress) {
        Consent consent = new Consent();
        consent.setUserId(userId);
        consent.setOrganizationId(organizationId);
        consent.setConsentType(type);
        consent.setPolicyVersion(CURRENT_POLICY_VERSION);
        consent.setTargetHostname(targetHostname);
        consent.setIpHash(hashIp(ipAddress));
        return consentRepository.save(consent);
    }

    private String hashIp(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(ipAddress.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256が利用できない", e);
        }
    }
}
