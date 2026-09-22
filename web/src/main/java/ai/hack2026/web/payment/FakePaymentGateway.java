package ai.hack2026.web.payment;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * v0.8第3章: 架空の決済(デモ)。本物の課金・外部決済APIは呼ばない
 * (CHANGE-v0.6.mdの禁止事項どおり)。テストカード{@code 4242 4242 4242 4242}のみ成功し、
 * それ以外は「カードが拒否されました」で失敗する(デモの失敗パターン)。
 * カード番号・期限・名義は、ここでも保存・ログ出力しない。
 */
@Component
public class FakePaymentGateway implements PaymentGateway {

    private static final String TEST_CARD_SUCCESS = "4242424242424242";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public PaymentResult charge(PaymentRequest request) {
        // 実際の決済のような処理時間を演出する(デモ。外部APIは呼ばない)。
        try {
            Thread.sleep(1200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String normalized = normalizeCardNumber(request.cardNumber());
        if (!TEST_CARD_SUCCESS.equals(normalized)) {
            return PaymentResult.failure("カードが拒否されました。テスト用のカード番号(4242 4242 4242 4242)をお試しください。");
        }
        return PaymentResult.success(generateReceiptNumber());
    }

    private static String normalizeCardNumber(String cardNumber) {
        return cardNumber == null ? "" : cardNumber.replaceAll("[\\s-]", "");
    }

    private static String generateReceiptNumber() {
        long n = Math.abs(RANDOM.nextLong()) % 1_000_000_000L;
        return "R-" + System.currentTimeMillis() + "-" + String.format("%09d", n);
    }
}
