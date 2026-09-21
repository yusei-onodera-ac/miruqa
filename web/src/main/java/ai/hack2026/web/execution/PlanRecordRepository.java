package ai.hack2026.web.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlanRecordRepository extends JpaRepository<PlanRecord, Long> {
    // テナント分離(U-4)と同じ方針: idだけ・planIdだけで取得するメソッドは用意しない
    Optional<PlanRecord> findByPlanIdAndOrganizationId(String planId, Long organizationId);
    List<PlanRecord> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);
    List<PlanRecord> findByProjectIdAndOrganizationIdOrderByCreatedAtDesc(Long projectId, Long organizationId);
}
