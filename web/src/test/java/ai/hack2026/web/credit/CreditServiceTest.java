package ai.hack2026.web.credit;

import ai.hack2026.web.auth.Membership;
import ai.hack2026.web.auth.MembershipRepository;
import ai.hack2026.web.auth.SignupService;
import ai.hack2026.web.auth.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.8第2章・第6章: クレジット制の換算・残高計算・二重課金防止(request_id冪等)を確認する。
 * 残高は可変な列を持たず、credit_ledgerの合計から都度計算する(イベントソーシング)。
 * credit_ledger.organization_idはorganizationsへのFK制約があるため、JobQueueServiceTestと
 * 同じ方針でSignupService経由の実データを使う。
 */
@SpringBootTest
@ActiveProfiles("test")
class CreditServiceTest {

    @Autowired
    private CreditLedgerRepository repository;
    @Autowired
    private SignupService signupService;
    @Autowired
    private MembershipRepository membershipRepository;

    private Long orgId;
    private Long otherOrgId;

    @BeforeEach
    void setUp() {
        orgId = createOrganization("credit-test");
        otherOrgId = createOrganization("credit-test-other");
    }

    private Long createOrganization(String label) {
        User owner = signupService.signUp(
                label + "-" + System.nanoTime() + "@example.com", "password123", "テスト担当", "テスト組織");
        Membership membership = membershipRepository.findByUserId(owner.getId()).get(0);
        return membership.getOrganization().getId();
    }

    private CreditService newService() {
        return new CreditService(repository, 150, 4.0, 50);
    }

    @Test
    void usdToCreditsAppliesRateAndMarkup() {
        CreditService service = newService();
        // 0.001 USD * 150円/USD * 4.0倍 = 0.6円 -> 四捨五入で1クレジット
        assertEquals(1, service.usdToCredits(0.001));
        // 0.01 USD * 150 * 4.0 = 6クレジット
        assertEquals(6, service.usdToCredits(0.01));
    }

    @Test
    void chargeIncreasesBalance() {
        CreditService service = newService();
        long before = service.getBalance(orgId);

        service.charge(orgId, 1000, "receipt-1", "1,000円チャージ");

        assertEquals(before + 1000, service.getBalance(orgId));
    }

    @Test
    void consumeDecreasesBalanceAndIsIdempotentByRequestId() {
        CreditService service = newService();
        service.charge(orgId, 1000, "receipt-2", "テスト用チャージ");
        long afterCharge = service.getBalance(orgId);

        boolean first = service.consume(orgId, "run-idem-1", "req-1", 0.01); // 6クレジット消費
        boolean second = service.consume(orgId, "run-idem-1", "req-1", 0.01); // 同じrequestId → 二重計上しない

        assertTrue(first);
        assertFalse(second, "同じ(runId, requestId)は二重に消費されないこと");
        assertEquals(afterCharge - 6, service.getBalance(orgId));
    }

    @Test
    void consumeWithDifferentRequestIdsBothApply() {
        CreditService service = newService();
        service.charge(orgId, 1000, "receipt-3", "テスト用チャージ");
        long afterCharge = service.getBalance(orgId);

        service.consume(orgId, "run-idem-2", "req-a", 0.01); // 6クレジット
        service.consume(orgId, "run-idem-2", "req-b", 0.01); // 6クレジット

        assertEquals(afterCharge - 12, service.getBalance(orgId));
    }

    @Test
    void minimumBalanceCheckReflectsThreshold() {
        CreditService service = newService(); // minBalanceToStart=50
        service.charge(orgId, 49, "receipt-4", "49クレジットのみ");
        assertFalse(service.hasMinimumBalance(orgId));

        service.charge(orgId, 1, "receipt-5", "追加1クレジット");
        assertTrue(service.hasMinimumBalance(orgId));
    }

    @Test
    void balanceIsScopedPerOrganization() {
        CreditService service = newService();
        service.charge(orgId, 500, "receipt-6", "組織Aのチャージ");

        assertEquals(0, service.getBalance(otherOrgId), "他組織の残高には影響しないこと");
    }
}
