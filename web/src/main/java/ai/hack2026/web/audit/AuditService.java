package ai.hack2026.web.audit;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void record(Long organizationId, Long userId, String action, String detail) {
        AuditLog log = new AuditLog();
        log.setOrganizationId(organizationId);
        log.setUserId(userId);
        log.setAction(action);
        log.setDetail(detail);
        repository.save(log);
    }

    public List<AuditLog> forOrganization(Long organizationId) {
        return repository.findByOrganizationIdOrderByCreatedAtDesc(organizationId);
    }
}
