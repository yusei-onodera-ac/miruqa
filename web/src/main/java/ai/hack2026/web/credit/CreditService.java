package ai.hack2026.web.credit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * v0.8第2章・第6章: クレジット制。USDはユーザー向け画面から全廃し、「クレジット」で表示する。
 * 1クレジット=1円相当。消費クレジット = 実LLM原価(USD) × {@link #usdToJpyRate} × {@link #markupMultiplier}
 * (2定数は設定値。コードに散らさない)。残高は{@link CreditLedgerEntry}の合計から都度計算する
 * (可変な残高列を持たないため、更新の競合・ズレが起きない。イベントソーシング)。
 *
 * <p>二重課金の防止(v0.8第6章): {@link #consume}は(runId, requestId)の組み合わせで冪等
 * (同じLLM呼び出しの消費を2回計上しない。再開時に、完了済みのLLM呼び出し分を再度引かない)。</p>
 */
@Service
public class CreditService {

    private static final Logger log = LoggerFactory.getLogger(CreditService.class);

    private final CreditLedgerRepository repository;
    private final double usdToJpyRate;
    private final double markupMultiplier;
    private final long minBalanceToStart;

    public CreditService(
            CreditLedgerRepository repository,
            @Value("${credit.usd-to-jpy-rate:150}") double usdToJpyRate,
            @Value("${credit.markup-multiplier:4.0}") double markupMultiplier,
            @Value("${credit.min-balance-to-start:50}") long minBalanceToStart) {
        this.repository = repository;
        this.usdToJpyRate = usdToJpyRate;
        this.markupMultiplier = markupMultiplier;
        this.minBalanceToStart = minBalanceToStart;
    }

    public long getBalance(Long organizationId) {
        return repository.sumBalance(organizationId);
    }

    public long getMinBalanceToStart() {
        return minBalanceToStart;
    }

    public boolean hasMinimumBalance(Long organizationId) {
        return getBalance(organizationId) >= minBalanceToStart;
    }

    public boolean hasPositiveBalance(Long organizationId) {
        return getBalance(organizationId) > 0;
    }

    /** USD原価をクレジットに換算する(表示・消費の両方で同じ計算を使う)。 */
    public long usdToCredits(double costUsd) {
        return Math.round(costUsd * usdToJpyRate * markupMultiplier);
    }

    public double getUsdToJpyRate() { return usdToJpyRate; }
    public double getMarkupMultiplier() { return markupMultiplier; }

    /** チャージ(架空決済の完了時に呼ぶ)。amountCreditsは正。 */
    public CreditLedgerEntry charge(Long organizationId, long amountCredits, String paymentReference, String detail) {
        CreditLedgerEntry entry = new CreditLedgerEntry();
        entry.setOrganizationId(organizationId);
        entry.setKind(CreditLedgerEntry.KIND_CHARGE);
        entry.setAmountCredits(amountCredits);
        entry.setPaymentReference(paymentReference);
        entry.setDetail(detail);
        return repository.save(entry);
    }

    /**
     * LLM呼び出し1回分の消費を計上する(冪等)。既に同じ(runId, requestId)を記録済みなら、
     * 何もしない(戻り値false)。costUsdが0以下(無料モデル等)でも、二重処理を防ぐために
     * 0クレジットの行を記録する(次回のポーリングで同じ呼び出しを再度「未処理」として
     * 拾わないようにするため)。
     */
    public boolean consume(Long organizationId, String runId, String requestId, double costUsd) {
        if (requestId == null || requestId.isBlank()) {
            return false;
        }
        if (repository.findByRunIdAndRequestId(runId, requestId).isPresent()) {
            return false;
        }
        long amount = usdToCredits(Math.max(costUsd, 0.0));
        CreditLedgerEntry entry = new CreditLedgerEntry();
        entry.setOrganizationId(organizationId);
        entry.setKind(CreditLedgerEntry.KIND_CONSUME);
        entry.setAmountCredits(-amount);
        entry.setRunId(runId);
        entry.setRequestId(requestId);
        try {
            repository.save(entry);
            return true;
        } catch (DataIntegrityViolationException e) {
            // 同時に2箇所から呼ばれても、UNIQUE制約により2重には残らない(UsageEventと同じ方針)。
            log.info("クレジット消費は既に記録済みだった(runId={}, requestId={})", runId, requestId);
            return false;
        }
    }

    public List<CreditLedgerEntry> history(Long organizationId) {
        return repository.findByOrganizationIdOrderByCreatedAtDesc(organizationId);
    }
}
