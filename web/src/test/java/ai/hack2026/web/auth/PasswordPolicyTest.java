package ai.hack2026.web.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** v0.6 P1補修(指揮官レビュー対応): パスワード強度の最小限チェック(PasswordPolicy)。 */
class PasswordPolicyTest {

    @Test
    void tooShortPasswordIsRejected() {
        assertThrows(PasswordPolicy.WeakPasswordException.class, () -> PasswordPolicy.validate("Ab1"));
    }

    @Test
    void nullPasswordIsRejected() {
        assertThrows(PasswordPolicy.WeakPasswordException.class, () -> PasswordPolicy.validate(null));
    }

    @Test
    void commonWeakPasswordIsRejected() {
        assertThrows(PasswordPolicy.WeakPasswordException.class, () -> PasswordPolicy.validate("password"));
    }

    @Test
    void commonWeakPasswordIsRejectedCaseInsensitively() {
        assertThrows(PasswordPolicy.WeakPasswordException.class, () -> PasswordPolicy.validate("PaSsWoRd1"));
    }

    @Test
    void strongEnoughPasswordIsAccepted() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("CorrectHorse9"));
    }
}
