package ai.hack2026.web.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** v0.6 P3(前半・指揮官指摘): ワーカーのSpec(specId)を、プロジェクト・組織に紐付けるメタデータ。
 * GET /specs/{specId}.jsonが組織の確認なしに任意のspecIdを返せてしまっていた不具合(IDOR)の是正。 */
@Entity
@Table(name = "spec_records")
public class SpecRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "spec_id", nullable = false, unique = true, length = 64)
    private String specId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getSpecId() { return specId; }
    public void setSpecId(String specId) { this.specId = specId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long organizationId) { this.organizationId = organizationId; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
