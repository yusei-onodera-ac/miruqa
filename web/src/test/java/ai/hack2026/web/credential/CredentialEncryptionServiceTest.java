package ai.hack2026.web.credential;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** v0.7 P5: パスワードの暗号化保管(AES/GCM)。docs/QUESTIONS.md #22⑤の仮定(環境変数の固定鍵、
 * KMS・ローテーションなし)どおり、鍵が未設定のときはfail-closedになることも確認する。 */
class CredentialEncryptionServiceTest {

    private static String randomBase64Key() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void encryptThenDecryptRoundTripsToOriginalPlaintext() {
        CredentialEncryptionService service = new CredentialEncryptionService(randomBase64Key());

        CredentialEncryptionService.Encrypted encrypted = service.encrypt("demo-pass-1");
        String decrypted = service.decrypt(encrypted.ciphertextBase64(), encrypted.ivBase64());

        assertEquals("demo-pass-1", decrypted);
    }

    @Test
    void ciphertextDoesNotContainPlaintext() {
        CredentialEncryptionService service = new CredentialEncryptionService(randomBase64Key());

        CredentialEncryptionService.Encrypted encrypted = service.encrypt("demo-pass-1");

        assertNotEquals("demo-pass-1", encrypted.ciphertextBase64());
        org.junit.jupiter.api.Assertions.assertFalse(encrypted.ciphertextBase64().contains("demo-pass-1"));
    }

    @Test
    void sameValueEncryptedTwiceProducesDifferentCiphertextAndIv() {
        // IVを毎回ランダムに生成しているため、同じ平文でも暗号文は毎回変わる(パターン推測を防ぐ)
        CredentialEncryptionService service = new CredentialEncryptionService(randomBase64Key());

        CredentialEncryptionService.Encrypted first = service.encrypt("demo-pass-1");
        CredentialEncryptionService.Encrypted second = service.encrypt("demo-pass-1");

        assertNotEquals(first.ciphertextBase64(), second.ciphertextBase64());
        assertNotEquals(first.ivBase64(), second.ivBase64());
    }

    @Test
    void encryptFailsClosedWhenKeyNotConfigured() {
        CredentialEncryptionService service = new CredentialEncryptionService("");

        assertThrows(IllegalStateException.class, () -> service.encrypt("demo-pass-1"));
    }

    @Test
    void decryptFailsClosedWhenKeyNotConfigured() {
        CredentialEncryptionService service = new CredentialEncryptionService("");

        assertThrows(IllegalStateException.class, () -> service.decrypt("anything", "anything"));
    }

    @Test
    void decryptWithWrongKeyFails() {
        CredentialEncryptionService service = new CredentialEncryptionService(randomBase64Key());
        CredentialEncryptionService.Encrypted encrypted = service.encrypt("demo-pass-1");
        CredentialEncryptionService otherService = new CredentialEncryptionService(randomBase64Key());

        assertThrows(IllegalStateException.class,
                () -> otherService.decrypt(encrypted.ciphertextBase64(), encrypted.ivBase64()));
    }
}
