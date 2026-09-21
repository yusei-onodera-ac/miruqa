package ai.hack2026.web.findings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FindingStatusRecordRepository extends JpaRepository<FindingStatusRecord, Long> {
    // テナント分離(U-4)と同じ方針: organizationIdと一緒に絞り込む。
    List<FindingStatusRecord> findByOrganizationIdAndProjectId(Long organizationId, Long projectId);
    Optional<FindingStatusRecord> findByOrganizationIdAndProjectIdAndFingerprint(
            Long organizationId, Long projectId, String fingerprint);
}
