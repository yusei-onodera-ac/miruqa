package ai.hack2026.web.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** ログイン中のユーザー(v0.6 P1)。0a章の規模(1アカウント=1組織)に合わせ、最初に作られた
 * 組織を「現在の組織」として持つ(将来、複数組織に対応する場合は組織切替UIを別途追加する)。 */
public class AppUserPrincipal implements UserDetails {

    private final Long userId;
    private final String email;
    private final String passwordHash;
    private final Long organizationId;
    private final Role role;
    private final Instant lockedUntil;

    public AppUserPrincipal(Long userId, String email, String passwordHash, Long organizationId, Role role, Instant lockedUntil) {
        this.userId = userId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.organizationId = organizationId;
        this.role = role;
        this.lockedUntil = lockedUntil;
    }

    public Long getUserId() { return userId; }
    public Long getOrganizationId() { return organizationId; }
    public Role getRole() { return role; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() { return passwordHash; }

    @Override
    public String getUsername() { return email; }

    @Override
    public boolean isAccountNonLocked() {
        // ログイン試行の回数制限(U-5): 一定回数失敗すると一時的にロックする(AuthFailureListener参照)
        return lockedUntil == null || lockedUntil.isBefore(Instant.now());
    }
}
