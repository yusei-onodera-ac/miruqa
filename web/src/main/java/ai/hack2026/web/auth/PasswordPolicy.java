package ai.hack2026.web.auth;

import java.util.Set;

/** パスワード強度の最小限のチェック(v0.6 P1補修、指揮官レビュー対応)。
 * 8文字以上、かつよくある弱いパスワードの拒否リストに一致しないこと。 */
public final class PasswordPolicy {

    private static final int MIN_LENGTH = 8;

    // 代表的な弱いパスワード(完全一致・大文字小文字を無視)。網羅的な辞書攻撃対策ではなく、
    // 明らかに弱いものを弾く最小限のチェック
    private static final Set<String> COMMON_WEAK_PASSWORDS = Set.of(
            "password", "password1", "12345678", "123456789", "qwerty123",
            "letmein", "welcome1", "administrator", "changeme", "iloveyou");

    private PasswordPolicy() {}

    public static class WeakPasswordException extends RuntimeException {
        public WeakPasswordException(String message) { super(message); }
    }

    /** 問題なければ何もしない。問題があれば具体的な理由つきで例外を投げる。 */
    public static void validate(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_LENGTH) {
            throw new WeakPasswordException("パスワードは" + MIN_LENGTH + "文字以上にしてください。");
        }
        if (COMMON_WEAK_PASSWORDS.contains(rawPassword.toLowerCase())) {
            throw new WeakPasswordException("よく使われる推測されやすいパスワードです。別のパスワードにしてください。");
        }
    }
}
