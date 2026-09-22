package ai.hack2026.web.payment;

/** v0.8第3章: 決済の結果。成功時はreceiptNumberのみ、失敗時はfailureReasonのみ意味を持つ。 */
public record PaymentResult(boolean success, String receiptNumber, String failureReason) {

    public static PaymentResult success(String receiptNumber) {
        return new PaymentResult(true, receiptNumber, null);
    }

    public static PaymentResult failure(String reason) {
        return new PaymentResult(false, null, reason);
    }
}
