package ai.hack2026.web.payment;

/**
 * v0.8第3章: 決済の依頼。カード情報はこのオブジェクトの外(DB・ログ)には出さない
 * (呼び出し元は、これをtoString()せず、保存もしないこと)。
 */
public record PaymentRequest(String cardNumber, String expiry, String cardHolderName, long amountYen) {

    @Override
    public String toString() {
        // 誤ってログに出されても、カード情報自体は残らないようにする(安全側の実装)。
        return "PaymentRequest{amountYen=" + amountYen + "}";
    }
}
