package ai.hack2026.web.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RunRecordRepository extends JpaRepository<RunRecord, Long> {
    // テナント分離(U-4)と同じ方針: idだけ・runIdだけで取得するメソッドは用意しない
    Optional<RunRecord> findByRunIdAndOrganizationId(String runId, Long organizationId);
    Optional<RunRecord> findByPlanIdAndOrganizationId(String planId, Long organizationId);
    List<RunRecord> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);
    List<RunRecord> findByProjectIdAndOrganizationIdOrderByCreatedAtDesc(Long projectId, Long organizationId);

    /** ジョブキュー(v0.6 P3)用: 組織内のアクティブな(queued/running)ジョブ、古い順(先に並んだ方から実行)。 */
    List<RunRecord> findByOrganizationIdAndStatusInOrderByCreatedAtAsc(Long organizationId, List<String> statuses);

    /** スケジューラの定期同期(全組織横断)用。 */
    List<RunRecord> findByStatusIn(List<String> statuses);
}
