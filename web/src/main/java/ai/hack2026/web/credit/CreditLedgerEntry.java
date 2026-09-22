package ai.hack2026.web.credit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** v0.8第2章・第6章: クレジット制の台帳(イベントソーシング)。残高は保持せず、組織ごとの
 * amountCreditsの合計として計算する({@link CreditService#getBalance}参照)。
 * kind=consumeの行は、(runId, requestId)にUNIQUE制約があり、同じLLM呼び出しの消費を
 * 二重に記録しない(再開時の二重課金防止。v0.8第6章)。 */
@Entity
@Table(name = "credit_ledger", uniqueConstraints = @UniqueConstraint(columnNames = {"run_id", "request_id"}))
public class CreditLedgerEntry {

    public static final String KIND_CHARGE = "charge";
    public static final String KIND_CONSUME = "consume";
    public static final String KIND_REFUND = "refund";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "kind", nullable = false, length = 20)
    private String kind;

    /** charge/refundは正、consumeは負。 */
    @Column(name = "amount_credits", nullable = false)
    private long amountCredits;

    @Column(name = "run_id", length = 64)
    private String runId;

    /** consume時のみ(LLM呼び出し単位の冪等キー)。 */
    @Column(name = "request_id", length = 128)
    private String requestId;

    /** charge時のみ(領収番号)。 */
    @Column(name = "payment_reference", length = 64)
    private String paymentReference;

    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long organizationId) { this.organizationId = organizationId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public long getAmountCredits() { return amountCredits; }
    public void setAmountCredits(long amountCredits) { this.amountCredits = amountCredits; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String paymentReference) { this.paymentReference = paymentReference; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
