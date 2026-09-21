package ai.hack2026.web.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** v0.6 P2(L-1): 対象ドメインの所有確認。組織+ホスト名の組で1件(同じ組織内の複数プロジェクトが
 * 同じドメインを使い回せるようにするため、プロジェクト単位では持たない)。 */
@Entity
@Table(name = "domains")
public class Domain {

    public static final String STATUS_UNVERIFIED = "unverified";
    public static final String STATUS_VERIFIED = "verified";
    public static final String STATUS_EXPIRED = "expired";
    /** v0.7(第1節、モードA): ローカル・プライベートIPの対象は所有を証明する方法が無いため、
     * /.well-known方式の所有確認は行わず、「開発中・リリース前のテスト環境であり、本番ではない」
     * ことの宣言(同意の記録)だけで診断を開始できるようにする。 */
    public static final String STATUS_LOCAL_DECLARED = "local_declared";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 255)
    private String hostname;

    @Column(nullable = false, length = 10)
    private String scheme = "http";

    @Column(nullable = false, length = 20)
    private String status = STATUS_UNVERIFIED;

    @Column(name = "verification_token", nullable = false, length = 64)
    private String verificationToken;

    // L-2: 所有者が「テスト環境である」と宣言したか(能動テスト(P-SEC)の許可条件の一部)
    @Column(name = "is_test_environment", nullable = false)
    private boolean testEnvironment = false;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long organizationId) { this.organizationId = organizationId; }
    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }
    public String getScheme() { return scheme; }
    public void setScheme(String scheme) { this.scheme = scheme; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getVerificationToken() { return verificationToken; }
    public void setVerificationToken(String verificationToken) { this.verificationToken = verificationToken; }
    public boolean isTestEnvironment() { return testEnvironment; }
    public void setTestEnvironment(boolean testEnvironment) { this.testEnvironment = testEnvironment; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(Instant lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    /** 診断そのものを開始してよいか(所有確認済み、またはモードAのローカル宣言済み)。 */
    public boolean isDiagnosisAllowed() {
        return STATUS_VERIFIED.equals(status) || STATUS_LOCAL_DECLARED.equals(status);
    }

    /** L-2: 能動テスト(P-SEC)を許可してよいか(所有確認済みまたはローカル宣言済み、かつ
     * テスト環境を宣言済み)。 */
    public boolean isActiveTestingAllowed() {
        return isDiagnosisAllowed() && testEnvironment;
    }
}
