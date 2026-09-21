package ai.hack2026.web.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 診断対象のサイト+仕様書+設定のまとまり(U-3)。組織に属する(テナント分離の単位)。
 * v0.7(第1b節): {@code kind}(DEV_ENV/PUBLIC_READONLY)は作成時に決め、以後は変更しない
 * (更新用のエンドポイント自体を用意しないことで担保する。読み取り専用のプロジェクトを
 * フルに切り替えて能動テストする抜け道をなくすため)。 */
@Entity
@Table(name = "projects")
public class Project {

    /** 開発環境の点検(フル)。ローカル、または所有確認済みの公開ステージング。 */
    public static final String KIND_DEV_ENV = "DEV_ENV";
    /** 公開ページの点検(読み取り専用)。任意の公開ページが対象。 */
    public static final String KIND_PUBLIC_READONLY = "PUBLIC_READONLY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "target_url", nullable = false, length = 500)
    private String targetUrl;

    @Column(nullable = false, length = 32)
    private String kind = KIND_DEV_ENV;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public boolean isPublicReadonly() { return KIND_PUBLIC_READONLY.equals(kind); }

    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long organizationId) { this.organizationId = organizationId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTargetUrl() { return targetUrl; }
    public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
