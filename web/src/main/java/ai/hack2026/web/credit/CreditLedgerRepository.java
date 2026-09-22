package ai.hack2026.web.credit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CreditLedgerRepository extends JpaRepository<CreditLedgerEntry, Long> {

    @Query("select coalesce(sum(c.amountCredits), 0) from CreditLedgerEntry c where c.organizationId = :organizationId")
    long sumBalance(@Param("organizationId") Long organizationId);

    List<CreditLedgerEntry> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    Optional<CreditLedgerEntry> findByRunIdAndRequestId(String runId, String requestId);
}
