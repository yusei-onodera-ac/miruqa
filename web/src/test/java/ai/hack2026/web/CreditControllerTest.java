package ai.hack2026.web;

import ai.hack2026.web.audit.AuditService;
import ai.hack2026.web.auth.AppUserPrincipal;
import ai.hack2026.web.auth.Role;
import ai.hack2026.web.credit.CreditPack;
import ai.hack2026.web.credit.CreditService;
import ai.hack2026.web.payment.PaymentGateway;
import ai.hack2026.web.payment.PaymentResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** v0.8第3章: 架空の決済(チャージ)。成功時だけ残高に反映され、失敗時はカード情報を
 * 何にも残さないことを確認する(SpringBootTestは使わず、依存をすべてMockitoでモックした
 * 素のユニットテスト。RunControllerStopTest等と同じ方針)。 */
class CreditControllerTest {

    private static final Long ORG_ID = 2L;
    private static final Long USER_ID = 1L;

    private final CreditService creditService = Mockito.mock(CreditService.class);
    private final PaymentGateway paymentGateway = Mockito.mock(PaymentGateway.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);
    private final CreditController controller = new CreditController(creditService, paymentGateway, auditService);
    private final AppUserPrincipal user = new AppUserPrincipal(USER_ID, "test@example.com", "hash", ORG_ID, Role.OWNER, null);

    @Test
    void successfulChargeIncreasesBalanceAndRecordsReceipt() {
        when(paymentGateway.charge(any())).thenReturn(PaymentResult.success("R-12345"));
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.charge(user, "pack-1000", "4242424242424242", "12/30", "TARO YAMADA", null, redirectAttributes);

        assertEquals("redirect:/credits/receipt", view);
        verify(creditService, times(1)).charge(eq(ORG_ID), eq(1000L), eq("R-12345"), anyString());
        verify(auditService, times(1)).record(anyLong(), anyLong(), eq("credit_charged"), anyString());
        assertEquals("R-12345", redirectAttributes.getFlashAttributes().get("receiptNumber"));
        assertEquals(1000L, redirectAttributes.getFlashAttributes().get("creditsGranted"));
    }

    @Test
    void declinedCardDoesNotChangeBalance() {
        when(paymentGateway.charge(any())).thenReturn(PaymentResult.failure("カードが拒否されました。"));
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.charge(user, "pack-1000", "4111111111111111", "12/30", "TARO YAMADA", null, redirectAttributes);

        assertEquals("redirect:/credits/charge", view);
        verify(creditService, never()).charge(any(), anyLong(), any(), any());
        verify(auditService, times(1)).record(anyLong(), anyLong(), eq("credit_charge_failed"), anyString());
        assertTrue(((String) redirectAttributes.getFlashAttributes().get("error")).contains("拒否"));
    }

    @Test
    void unknownPackIsRejectedWithoutCallingPaymentGateway() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.charge(user, "pack-does-not-exist", "4242424242424242", "12/30", "TARO", null, redirectAttributes);

        assertEquals("redirect:/credits/charge", view);
        verify(paymentGateway, never()).charge(any());
        verify(creditService, never()).charge(any(), anyLong(), any(), any());
    }

    @Test
    void cardDetailsAreNeverPassedToCreditServiceOrAuditService() {
        when(paymentGateway.charge(any())).thenReturn(PaymentResult.success("R-99999"));
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String secretCardNumber = "4242424242424242";
        String secretHolderName = "SECRET HOLDER NAME";

        controller.charge(user, "pack-1000", secretCardNumber, "12/30", secretHolderName, null, redirectAttributes);

        ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);
        verify(creditService).charge(anyLong(), anyLong(), anyString(), detailCaptor.capture());
        assertTrue(!detailCaptor.getValue().contains(secretCardNumber) && !detailCaptor.getValue().contains(secretHolderName));

        ArgumentCaptor<String> auditDetailCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(anyLong(), anyLong(), eq("credit_charged"), auditDetailCaptor.capture());
        assertTrue(!auditDetailCaptor.getValue().contains(secretCardNumber) && !auditDetailCaptor.getValue().contains(secretHolderName));
    }

    @Test
    void chargeFormExposesPacksAndBalance() {
        when(creditService.getBalance(ORG_ID)).thenReturn(500L);
        Model model = new ExtendedModelMap();

        String view = controller.chargeForm(user, null, model);

        assertEquals("credit/charge", view);
        assertEquals(500L, model.getAttribute("creditBalance"));
        assertEquals(CreditPack.ALL, model.getAttribute("packs"));
    }

    /** 指揮官バグ報告(2026-09-22)対応: 残高不足からのチャージは、元のプラン画面(returnTo)へ
     * 戻れること。オープンリダイレクト対策として、決まった形(/plans/{id})以外は無視すること
     * の両方を確認する。 */
    @Test
    void chargeFormPassesThroughASafeReturnTo() {
        when(creditService.getBalance(ORG_ID)).thenReturn(500L);
        Model model = new ExtendedModelMap();

        controller.chargeForm(user, "/plans/p-123", model);

        assertEquals("/plans/p-123", model.getAttribute("returnTo"));
    }

    @Test
    void chargeFormIgnoresAnUnsafeReturnToToPreventOpenRedirect() {
        when(creditService.getBalance(ORG_ID)).thenReturn(500L);
        Model model = new ExtendedModelMap();

        controller.chargeForm(user, "https://evil.example.com/", model);

        assertEquals(null, model.getAttribute("returnTo"));
    }

    @Test
    void successfulChargeCarriesReturnToThroughToTheReceiptFlashAttributes() {
        when(paymentGateway.charge(any())).thenReturn(PaymentResult.success("R-12345"));
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        controller.charge(user, "pack-1000", "4242424242424242", "12/30", "TARO YAMADA", "/plans/p-123", redirectAttributes);

        assertEquals("/plans/p-123", redirectAttributes.getFlashAttributes().get("returnTo"));
    }

    @Test
    void successfulChargeDropsAnUnsafeReturnTo() {
        when(paymentGateway.charge(any())).thenReturn(PaymentResult.success("R-12345"));
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        controller.charge(user, "pack-1000", "4242424242424242", "12/30", "TARO YAMADA",
                "https://evil.example.com/", redirectAttributes);

        assertEquals(null, redirectAttributes.getFlashAttributes().get("returnTo"));
    }
}
