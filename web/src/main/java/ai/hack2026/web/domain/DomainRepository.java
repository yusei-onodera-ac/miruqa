package ai.hack2026.web.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DomainRepository extends JpaRepository<Domain, Long> {
    // テナント分離(U-4)と同じ方針: idだけで取得するメソッドは用意しない
    List<Domain> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);
    Optional<Domain> findByIdAndOrganizationId(Long id, Long organizationId);
    Optional<Domain> findByOrganizationIdAndHostname(Long organizationId, String hostname);
}
