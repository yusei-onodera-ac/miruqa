package ai.hack2026.web.usage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UsageEventRepository extends JpaRepository<UsageEvent, Long> {
    Optional<UsageEvent> findByRunId(String runId);

    List<UsageEvent> findByOrganizationIdAndRecordedAtAfter(Long organizationId, Instant after);
}
