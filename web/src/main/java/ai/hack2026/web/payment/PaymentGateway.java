package ai.hack2026.web.payment;

/**
 * v0.8第3章: 架空の決済。将来、本物の決済(Stripe等)に差し替え可能な形にするための境界。
 * 本物の外部決済APIは呼ばない(CHANGE-v0.6.mdの禁止事項どおり)。カード情報は、実装側で
 * 保存もログ出力もしないこと(呼び出し元・実装のどちらでも、平文のカード番号を残さない)。
 */
public interface PaymentGateway {

    PaymentResult charge(PaymentRequest request);
}
