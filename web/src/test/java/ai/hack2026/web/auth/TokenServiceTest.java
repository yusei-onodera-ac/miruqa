package ai.hack2026.web.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v0.6 P1補修(指揮官レビュー対応): メール確認・パスワード再設定トークンの
 * 期限切れ拒否・使用済み再利用拒否を確認する(TokenService)。 */
@SpringBootTest
@ActiveProfiles("test")
class TokenServiceTest {

    @Autowired
    private TokenService tokenService;
    @Autowired
    private AuthTokenRepository authTokenRepository;
    @Autowired
    private SignupService signupService;

    private Long freshUserId() {
        String email = "token-test-" + System.nanoTime() + "@example.com";
        User user = signupService.signUp(email, "password123", "テスト担当", "テスト組織");
        return user.getId();
    }

    @Test
    void issuedTokenVerifiesSuccessfully() {
        Long userId = freshUserId();
        String raw = tokenService.issue(userId, AuthToken.PURPOSE_EMAIL_VERIFY);

        Optional<AuthToken> found = tokenService.verify(raw, AuthToken.PURPOSE_EMAIL_VERIFY);

        assertTrue(found.isPresent());
        assertTrue(found.get().isValid());
    }

    @Test
    void expiredTokenIsRejected() {
        Long userId = freshUserId();
        String raw = tokenService.issue(userId, AuthToken.PURPOSE_PASSWORD_RESET);
        AuthToken token = tokenService.verify(raw, AuthToken.PURPOSE_PASSWORD_RESET).orElseThrow();
        token.setExpiresAt(Instant.now().minusSeconds(60));
        authTokenRepository.save(token);

        Optional<AuthToken> result = tokenService.verify(raw, AuthToken.PURPOSE_PASSWORD_RESET);

        assertFalse(result.isPresent());
    }

    @Test
    void usedTokenCannotBeReused() {
        Long userId = freshUserId();
        String raw = tokenService.issue(userId, AuthToken.PURPOSE_EMAIL_VERIFY);
        AuthToken token = tokenService.verify(raw, AuthToken.PURPOSE_EMAIL_VERIFY).orElseThrow();

        tokenService.consume(token);

        Optional<AuthToken> result = tokenService.verify(raw, AuthToken.PURPOSE_EMAIL_VERIFY);
        assertFalse(result.isPresent());
    }

    @Test
    void wrongPurposeIsRejected() {
        Long userId = freshUserId();
        String raw = tokenService.issue(userId, AuthToken.PURPOSE_EMAIL_VERIFY);

        Optional<AuthToken> result = tokenService.verify(raw, AuthToken.PURPOSE_PASSWORD_RESET);

        assertFalse(result.isPresent());
    }

    @Test
    void unknownTokenIsRejected() {
        Optional<AuthToken> result = tokenService.verify("this-token-was-never-issued", AuthToken.PURPOSE_EMAIL_VERIFY);

        assertFalse(result.isPresent());
    }
}
