package ai.hack2026.web.credential;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TestCredentialRepository extends JpaRepository<TestCredential, Long> {
    // テナント分離(U-4)と同じ方針: organizationIdと一緒に絞り込む。
    Optional<TestCredential> findByProjectIdAndOrganizationId(Long projectId, Long organizationId);
    void deleteByProjectIdAndOrganizationId(Long projectId, Long organizationId);
}
