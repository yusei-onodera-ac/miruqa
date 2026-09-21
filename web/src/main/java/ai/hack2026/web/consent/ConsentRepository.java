package ai.hack2026.web.consent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConsentRepository extends JpaRepository<Consent, Long> {
    List<Consent> findByUserIdAndConsentTypeOrderByCreatedAtDesc(Long userId, String consentType);
}
