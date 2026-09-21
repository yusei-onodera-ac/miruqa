package ai.hack2026.web.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/** 模擬メール送信(U-1)。本物のメールは送らず、リンク入りのファイルをoutbox/に出力する(.gitignore対象)。
 * v0.6第8章「本物のメール送信は、しない」を守るための実装。 */
@Service
public class MockMailService {

    private final Path outboxDir;

    public MockMailService(@Value("${mail.outbox-dir}") String outboxDir) {
        this.outboxDir = Path.of(outboxDir);
    }

    public void send(String to, String subject, String body) {
        try {
            Files.createDirectories(outboxDir);
            String filename = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").format(
                    Instant.now().atZone(java.time.ZoneOffset.UTC)) + "-" + sanitize(to) + ".txt";
            String content = "To: " + to + "\nSubject: " + subject + "\n\n" + body + "\n";
            Files.writeString(outboxDir.resolve(filename), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 模擬送信の失敗でアプリを落とさない(FR-30と同じ思想)。ログにだけ残す
            System.err.println("[mock-mail] failed to write outbox file: " + e);
        }
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9@._-]", "_");
    }
}
