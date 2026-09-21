package ai.hack2026.web.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpecRecordRepository extends JpaRepository<SpecRecord, Long> {
    // テナント分離(U-4)と同じ方針: idだけ・specIdだけで取得するメソッドは用意しない
    Optional<SpecRecord> findBySpecIdAndOrganizationId(String specId, Long organizationId);
    List<SpecRecord> findByProjectIdAndOrganizationId(Long projectId, Long organizationId);
}
