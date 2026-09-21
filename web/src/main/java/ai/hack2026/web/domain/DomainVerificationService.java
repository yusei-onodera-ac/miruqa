package ai.hack2026.web.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/** v0.6 P2(L-1): 対象ドメインの所有確認(模擬をやめる)。
 * `/.well-known/<サービス名>-verification.txt` に、発行したトークンと同じ内容のファイルを
 * 対象サイト側に置いてもらい、実際にHTTPで取得して照合する。 */
@Service
public class DomainVerificationService {

    private static final Logger log = LoggerFactory.getLogger(DomainVerificationService.class);

    private final DomainRepository domainRepository;
    private final DomainSafetyChecker safetyChecker;
    private final HostnameDenylist denylist;
    private final String serviceName;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER) // 確認先の遷移をそのまま信用しない
            .build();

    public DomainVerificationService(
            DomainRepository domainRepository,
            DomainSafetyChecker safetyChecker,
            HostnameDenylist denylist,
            @Value("${domain-verification.service-name:miruqa}") String serviceName) {
        this.domainRepository = domainRepository;
        this.safetyChecker = safetyChecker;
        this.denylist = denylist;
        this.serviceName = serviceName;
    }

    public String verificationFileName() {
        return serviceName + "-verification.txt";
    }

    public static class DeniedHostnameException extends RuntimeException {
        public DeniedHostnameException(String message) { super(message); }
    }

    /** 組織+ホスト名(host[:port])の確認レコードを取得、無ければトークンを発行して新規作成する。
     * 拒否リスト(L-4)に一致するホストは、ALLOWED_HOSTS相当の設定に関わらず登録自体を拒否する。 */
    @Transactional
    public Domain startVerification(Long organizationId, String hostname, String scheme) {
        String hostOnly = hostname.contains(":") ? hostname.substring(0, hostname.lastIndexOf(':')) : hostname;
        denylist.deniedReason(hostOnly).ifPresent(reason -> {
            throw new DeniedHostnameException(reason);
        });
        return domainRepository.findByOrganizationIdAndHostname(organizationId, hostname)
                .orElseGet(() -> {
                    Domain domain = new Domain();
                    domain.setOrganizationId(organizationId);
                    domain.setHostname(hostname);
                    domain.setScheme(scheme == null || scheme.isBlank() ? "http" : scheme);
                    domain.setVerificationToken(generateToken());
                    return domainRepository.save(domain);
                });
    }

    private String generateToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 実際に対象サイトへHTTPで取得しにいき、トークンが一致するか確認する。
     * SSRF対策として、名前解決した先が遮断対象なら取得自体を行わない(未確認のまま)。 */
    @Transactional
    public Domain checkVerification(Domain domain) {
        domain.setLastCheckedAt(Instant.now());
        Optional<String> blocked = safetyChecker.checkHostnameSafe(domain.getHostname());
        if (blocked.isPresent()) {
            log.warn("ドメイン確認を拒否した(SSRF対策): hostname={} reason={}", domain.getHostname(), blocked.get());
            return domainRepository.save(domain);
        }
        boolean matched = fetchAndCompare(domain);
        if (matched) {
            domain.setStatus(Domain.STATUS_VERIFIED);
            domain.setVerifiedAt(Instant.now());
        } else {
            domain.setStatus(Domain.STATUS_UNVERIFIED);
        }
        return domainRepository.save(domain);
    }

    private boolean fetchAndCompare(Domain domain) {
        String url = domain.getScheme() + "://" + domain.getHostname() + "/.well-known/" + verificationFileName();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return false;
            }
            return domain.getVerificationToken().equals(response.body().trim());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.info("ドメイン確認の取得に失敗した(接続不可・タイムアウト等): hostname={} error={}", domain.getHostname(), e.toString());
            return false;
        }
    }
}
