package ai.hack2026.web.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** v0.6 P2(L-4、指揮官指摘 2026-09-21): 審査員・協賛企業・政府機関等のホスト名の拒否リスト。
 * agent/security.py(ワーカー側)と同じ既定値を持つ。ドメイン登録(所有確認の開始)を、
 * ALLOWED_HOSTS相当の設定に関わらず拒否する、Java側での二重の防御。 */
@Component
public class HostnameDenylist {

    private final java.util.List<String> suffixes;
    private final Set<String> exactHosts;

    public HostnameDenylist(
            @Value("${denylist.hostname-suffixes:.go.jp,.lg.jp}") String suffixesRaw,
            @Value("${denylist.hostnames:cyberace.co.jp,orcarouter.ai}") String hostsRaw) {
        this.suffixes = Arrays.stream(suffixesRaw.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toList();
        this.exactHosts = new HashSet<>();
        for (String h : hostsRaw.split(",")) {
            String trimmed = h.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                this.exactHosts.add(trimmed);
            }
        }
    }

    /** ホスト名(ポートを含まない)が拒否リストに一致するか。一致すれば理由を返す(無ければempty=許可)。 */
    public Optional<String> deniedReason(String hostname) {
        String hostLower = hostname.toLowerCase(Locale.ROOT);
        for (String suffix : suffixes) {
            if (hostLower.endsWith(suffix)) {
                return Optional.of("拒否リスト(政府・公共機関等のドメイン: " + suffix + ")に一致した");
            }
        }
        for (String denied : exactHosts) {
            if (hostLower.equals(denied) || hostLower.endsWith("." + denied)) {
                return Optional.of("拒否リスト(審査員・協賛企業等: " + denied + ")に一致した");
            }
        }
        return Optional.empty();
    }
}
