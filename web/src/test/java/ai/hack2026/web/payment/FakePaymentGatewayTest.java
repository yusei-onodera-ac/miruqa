package ai.hack2026.web.payment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.8第3章: 架空の決済(デモ)。テストカード4242 4242 4242 4242のみ成功し、
 * それ以外は「カードが拒否されました」で失敗することを確認する。 */
class FakePaymentGatewayTest {

    private final FakePaymentGateway gateway = new FakePaymentGateway();

    @Test
    void testCardWithSpacesSucceeds() {
        PaymentResult result = gateway.charge(new PaymentRequest("4242 4242 4242 4242", "12/30", "TARO YAMADA", 1000));

        assertTrue(result.success());
        assertNotNull(result.receiptNumber());
        assertNull(result.failureReason());
    }

    @Test
    void testCardWithoutSpacesSucceeds() {
        PaymentResult result = gateway.charge(new PaymentRequest("4242424242424242", "12/30", "TARO YAMADA", 1000));

        assertTrue(result.success());
        assertNotNull(result.receiptNumber());
    }

    @Test
    void otherCardIsDeclined() {
        PaymentResult result = gateway.charge(new PaymentRequest("4111 1111 1111 1111", "12/30", "TARO YAMADA", 1000));

        assertFalse(result.success());
        assertNull(result.receiptNumber());
        assertNotNull(result.failureReason());
        assertTrue(result.failureReason().contains("拒否"));
    }

    @Test
    void eachSuccessfulChargeGetsAUniqueReceiptNumber() {
        PaymentResult first = gateway.charge(new PaymentRequest("4242424242424242", "12/30", "A", 1000));
        PaymentResult second = gateway.charge(new PaymentRequest("4242424242424242", "12/30", "B", 1000));

        assertFalse(first.receiptNumber().equals(second.receiptNumber()));
    }

    @Test
    void requestToStringNeverContainsCardDetails() {
        PaymentRequest request = new PaymentRequest("4242424242424242", "12/30", "TARO YAMADA", 1000);

        String text = request.toString();

        assertFalse(text.contains("4242"));
        assertFalse(text.contains("TARO"));
        assertFalse(text.contains("12/30"));
    }
}
