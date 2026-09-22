package ai.hack2026.web.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** v0.6 P3(前半): ワーカーのPlan(planId)を、プロジェクト・組織に紐付けるメタデータ。
 * Plan本体(下見結果・テスト項目書)は引き続きワーカー側のJSONが正。ここではテナント分離
 * (組織で絞り込み・別組織のIDで404)のためのひもづけだけを持つ。 */
@Entity
@Table(name = "plan_records")
public class PlanRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false, unique = true, length = 64)
    private String planId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** v0.8第4章: 「前回の結果を引き継ぐ」を選んだときの、比較対象の前回Run(Java側の安定した
     * runId)。設定されていれば、この実行の結果画面で前回との差分を表示する。 */
    @Column(name = "carried_over_from_run_id", length = 64)
    private String carriedOverFromRunId;

    public Long getId() { return id; }
    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long organizationId) { this.organizationId = organizationId; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getCarriedOverFromRunId() { return carriedOverFromRunId; }
    public void setCarriedOverFromRunId(String carriedOverFromRunId) { this.carriedOverFromRunId = carriedOverFromRunId; }
}
