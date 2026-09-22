package ai.hack2026.web.credential;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/** v0.7 P5: 対象サイトのテスト用アカウントのパスワードを、保管前に暗号化する
 * (docs/QUESTIONS.md #22⑤の仮定: 環境変数の固定鍵。KMS・ローテーションはロードマップ)。
 * AES/GCM/NoPadding(256bit鍵、96bit IV、認証タグ付き)。鍵は{@code CREDENTIAL_ENCRYPTION_KEY}
 * (Base64、32バイト)。未設定のときは、ワーカーの共有秘密(WORKER_SHARED_SECRET)と同じ方針で
 * fail-closed(保存・復号のどちらも例外にする。平文のまま保存してしまう事故を防ぐ)。 */
@Service
public class CredentialEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int IV_LENGTH_BYTES = 12;

    private final SecretKey key;

    public CredentialEncryptionService(@Value("${credential.encryption-key:}") String base64Key) {
        this.key = base64Key.isBlank() ? null : new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES");
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "CREDENTIAL_ENCRYPTION_KEY が設定されていません。テスト用アカウントの暗号化保管には必須です(fail-closed)。");
        }
    }

    /** 戻り値: {@code {ciphertext(Base64), iv(Base64)}}。 */
    public record Encrypted(String ciphertextBase64, String ivBase64) {
    }

    public Encrypted encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(Base64.getEncoder().encodeToString(ciphertext), Base64.getEncoder().encodeToString(iv));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("テスト用アカウントの暗号化に失敗しました", e);
        }
    }

    public String decrypt(String ciphertextBase64, String ivBase64) {
        requireKey();
        try {
            byte[] iv = Base64.getDecoder().decode(ivBase64);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(Base64.getDecoder().decode(ciphertextBase64));
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("テスト用アカウントの復号に失敗しました", e);
        }
    }
}
