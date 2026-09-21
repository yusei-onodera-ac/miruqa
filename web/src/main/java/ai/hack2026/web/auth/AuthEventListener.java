package ai.hack2026.web.auth;

import ai.hack2026.web.audit.AuditService;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** ログイン試行の回数制限(U-5)。既定5回失敗で15分ロックする。成功したらカウントをリセットする。 */
@Component
public class AuthEventListener {

    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCK_MINUTES = 15;

    private final UserRepository userRepository;
    private final AuditService auditService;

    public AuthEventListener(UserRepository userRepository, AuditService auditService) {
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        String email = String.valueOf(event.getAuthentication().getPrincipal());
        userRepository.findByEmail(email).ifPresent(user -> {
            user.setFailedLoginCount(user.getFailedLoginCount() + 1);
            if (user.getFailedLoginCount() >= MAX_ATTEMPTS) {
                user.setLockedUntil(Instant.now().plus(LOCK_MINUTES, ChronoUnit.MINUTES));
                auditService.record(null, user.getId(), "login_locked",
                        "ログイン試行が" + MAX_ATTEMPTS + "回失敗したため" + LOCK_MINUTES + "分ロックしました");
            }
            userRepository.save(user);
        });
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        if (event.getAuthentication().getPrincipal() instanceof AppUserPrincipal principal) {
            userRepository.findById(principal.getUserId()).ifPresent(user -> {
                user.setFailedLoginCount(0);
                user.setLockedUntil(null);
                userRepository.save(user);
            });
            auditService.record(principal.getOrganizationId(), principal.getUserId(), "login_success", null);
        }
    }
}
