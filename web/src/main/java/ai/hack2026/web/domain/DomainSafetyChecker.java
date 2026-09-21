package ai.hack2026.web.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** v0.6 P2(L-1/S-2): ドメイン所有確認のために、対象ホストへHTTPで確認しにいく前のSSRF対策。
 * agent/security.py(ワーカー側)と同じ考え方(名前解決した先のIPが、ループバック・プライベート・
 * リンクローカル・クラウドのメタデータアドレスでないかを確認する)をJava側にも用意する。
 * `domain-verification.sandbox-hosts`に完全一致するホスト名(host[:port]、自作デモサイト用)だけは
 * 明示的な例外として、名前解決チェックをスキップする。 */
@Component
public class DomainSafetyChecker {

    private final Set<String> sandboxHosts;

    public DomainSafetyChecker(@Value("${domain-verification.sandbox-hosts:127.0.0.1:8765}") String sandboxHostsRaw) {
        this.sandboxHosts = new HashSet<>(Arrays.asList(sandboxHostsRaw.split(",")));
        this.sandboxHosts.removeIf(String::isBlank);
    }

    public boolean isSandboxHost(String hostname) {
        return sandboxHosts.contains(hostname);
    }

    /** IPアドレスの集合のうち、遮断すべきものがあれば理由を返す(無ければempty=安全)。
     * DNS解決に依存しない純粋なロジックなので、リテラルIPのInetAddressで直接テストできる。 */
    public Optional<String> blockedReason(InetAddress[] addresses) {
        for (InetAddress addr : addresses) {
            if (addr.isLoopbackAddress()) {
                return Optional.of("ループバックアドレス");
            }
            if ("169.254.169.254".equals(addr.getHostAddress())) {
                return Optional.of("クラウドのメタデータアドレス");
            }
            if (addr.isLinkLocalAddress()) {
                return Optional.of("リンクローカルアドレス");
            }
            if (addr.isSiteLocalAddress()) {
                return Optional.of("プライベートアドレス");
            }
            if (addr.isMulticastAddress() || addr.isAnyLocalAddress()) {
                return Optional.of("予約・特殊用途アドレス");
            }
        }
        return Optional.empty();
    }

    /** host[:port]形式のホスト名を実際に名前解決し、安全か判定する。安全ならempty、
     * 遮断すべきなら理由を返す。サンドボックス例外に該当する場合は名前解決を行わずemptyを返す。 */
    public Optional<String> checkHostnameSafe(String hostnameWithPort) {
        if (isSandboxHost(hostnameWithPort)) {
            return Optional.empty();
        }
        String host = hostnameWithPort.contains(":")
                ? hostnameWithPort.substring(0, hostnameWithPort.lastIndexOf(':'))
                : hostnameWithPort;
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            return blockedReason(addresses);
        } catch (UnknownHostException e) {
            return Optional.of("名前解決に失敗した(" + e.getMessage() + ")");
        }
    }

    /** v0.7: 対象がローカル・プライベート(モードA向け)か、公開(モードB/C向け)かを判定する。
     * クラウドのメタデータアドレス・多重放送等の特殊用途アドレスは、どちらのモードでも常に遮断
     * するためBLOCKEDにする(HostnameDenylistとは別の判定軸。呼び出し側で両方確認すること)。 */
    public enum HostClass { LOCAL_OR_PRIVATE, PUBLIC, BLOCKED }

    public HostClass classify(String hostnameWithPort) {
        String host = hostnameWithPort.contains(":")
                ? hostnameWithPort.substring(0, hostnameWithPort.lastIndexOf(':'))
                : hostnameWithPort;
        String lower = host.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".local") || lower.endsWith(".test")) {
            return HostClass.LOCAL_OR_PRIVATE;
        }
        if (isSandboxHost(hostnameWithPort)) {
            return HostClass.LOCAL_OR_PRIVATE;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return HostClass.BLOCKED;
        }
        Optional<String> blocked = blockedReason(addresses);
        if (blocked.isEmpty()) {
            return HostClass.PUBLIC;
        }
        String reason = blocked.get();
        if ("ループバックアドレス".equals(reason) || "プライベートアドレス".equals(reason)
                || "リンクローカルアドレス".equals(reason)) {
            return HostClass.LOCAL_OR_PRIVATE;
        }
        // クラウドのメタデータアドレス・予約/特殊用途アドレスは、どちらのモードでも常に遮断
        return HostClass.BLOCKED;
    }
}
