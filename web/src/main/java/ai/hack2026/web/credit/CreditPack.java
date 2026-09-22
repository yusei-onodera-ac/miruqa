package ai.hack2026.web.credit;

import java.util.List;

/** v0.8第2章・第3章: チャージパック(案)。1,000円=1,000cr、3,000円=3,300cr(ボーナス付き)、
 * 10,000円=11,500cr(ボーナス付き)。月額固定なし・使った分だけ。 */
public record CreditPack(String id, long yenAmount, long credits) {

    public static final List<CreditPack> ALL = List.of(
            new CreditPack("pack-1000", 1000, 1000),
            new CreditPack("pack-3000", 3000, 3300),
            new CreditPack("pack-10000", 10000, 11500)
    );

    public static CreditPack byId(String id) {
        return ALL.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }

    public long bonusCredits() {
        return credits - yenAmount;
    }
}
