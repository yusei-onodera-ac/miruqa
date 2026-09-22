package ai.hack2026.web.auth;

import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;

/** メール確認・パスワード再設定のワンタイムトークン発行・検証(v0.6 P1補修、指揮官レビュー対応)。
 * 平文トークンはDBに保存せず、SHA-256ハッシュだけを保存する(トークンはメール本文(模擬)にだけ載る)。 */
@Service
public class TokenService {

    private static final long EMAIL_VERIFY_TTL_HOURS = 24;
    private static final long PASSWORD_RESET_TTL_MINUTES = 30;

    private final AuthTokenRepository repository;
    private final SecureRandom random = new SecureRandom();

    public TokenService(AuthTokenRepository repository) {
        this.repository = repository;
    }

    /** 新しいトークンを発行し、平文トークン(メールに載せる用)を返す。DBにはハッシュだけ保存する。 */
    public String issue(Long userId, String purpose) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        AuthToken token = new AuthToken();
        token.setUserId(userId);
        token.setTokenHash(hash(rawToken));
        token.setPurpose(purpose);
        long ttlMinutes = AuthToken.PURPOSE_EMAIL_VERIFY.equals(purpose) ? EMAIL_VERIFY_TTL_HOURS * 60 : PASSWORD_RESET_TTL_MINUTES;
        token.setExpiresAt(Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES));
        repository.save(token);
        return rawToken;
    }

    /** トークンを検証する(有効期限内・未使用・目的一致)。有効ならそのAuthTokenを返す(まだ消費しない)。 */
    public Optional<AuthToken> verify(String rawToken, String purpose) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return repository.findByTokenHash(hash(rawToken))
                .filter(t -> purpose.equals(t.getPurpose()))
                .filter(AuthToken::isValid);
    }

    /** トークンを使用済みにする(ワンタイム性の担保)。 */
    public void consume(AuthToken token) {
        token.setUsedAt(Instant.now());
        repository.save(token);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256が利用できない", e);
        }
    }
}
