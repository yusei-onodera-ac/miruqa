package ai.hack2026.web.domain;

import ai.hack2026.web.auth.MembershipRepository;
import ai.hack2026.web.auth.SignupService;
import ai.hack2026.web.auth.User;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** v0.6 P2(L-1): ドメイン所有確認の成否(DomainVerificationService)。実際にPlaywrightは使わず、
 * com.sun.net.httpserverの軽量なローカルサーバーへ本物のHTTP取得を行って検証する
 * (対象サイトの/.well-known/配下のファイルを実際に取得して照合する、という仕組みそのものを確認する)。 */
@SpringBootTest
@ActiveProfiles("test")
class DomainVerificationServiceTest {

    @Autowired
    private DomainRepository domainRepository;
    @Autowired
    private SignupService signupService;
    @Autowired
    private MembershipRepository membershipRepository;

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private Long freshOrgId() {
        String email = "domain-test-" + System.nanoTime() + "@example.com";
        User user = signupService.signUp(email, "password123", "テスト担当", "テスト組織" + System.nanoTime());
        return membershipRepository.findByUserId(user.getId()).get(0).getOrganization().getId();
    }

    @Test
    void matchingFileContentMarksDomainVerified() throws IOException {
        Long orgId = freshOrgId();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String hostname = "127.0.0.1:" + port;

        // 確認先のこのホストだけをサンドボックス扱いにする(実際の名前解決チェックはスキップされる。
        // 127.0.0.1自体はループバックのため、通常はSSRF対策で遮断される)
        DomainSafetyChecker safetyChecker = new DomainSafetyChecker(hostname);
        DomainVerificationService service = new DomainVerificationService(domainRepository, safetyChecker, new HostnameDenylist(".go.jp,.lg.jp", "cyberace.co.jp,orcarouter.ai"), "site-inspector");

        Domain domain = service.startVerification(orgId, hostname, "http");
        server.createContext("/.well-known/site-inspector-verification.txt", exchange -> {
            byte[] bytes = domain.getVerificationToken().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        Domain result = service.checkVerification(domain);

        assertEquals(Domain.STATUS_VERIFIED, result.getStatus());
        assertNotNull(result.getVerifiedAt());
    }

    @Test
    void wrongFileContentLeavesDomainUnverified() throws IOException {
        Long orgId = freshOrgId();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String hostname = "127.0.0.1:" + port;

        DomainSafetyChecker safetyChecker = new DomainSafetyChecker(hostname);
        DomainVerificationService service = new DomainVerificationService(domainRepository, safetyChecker, new HostnameDenylist(".go.jp,.lg.jp", "cyberace.co.jp,orcarouter.ai"), "site-inspector");

        Domain domain = service.startVerification(orgId, hostname, "http");
        server.createContext("/.well-known/site-inspector-verification.txt", exchange -> {
            byte[] bytes = "wrong-token".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        Domain result = service.checkVerification(domain);

        assertEquals(Domain.STATUS_UNVERIFIED, result.getStatus());
        assertNull(result.getVerifiedAt());
    }

    @Test
    void missingFileLeavesDomainUnverified() throws IOException {
        Long orgId = freshOrgId();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String hostname = "127.0.0.1:" + port;
        server.start(); // /.well-known/配下に何も登録しないので404になる

        DomainSafetyChecker safetyChecker = new DomainSafetyChecker(hostname);
        DomainVerificationService service = new DomainVerificationService(domainRepository, safetyChecker, new HostnameDenylist(".go.jp,.lg.jp", "cyberace.co.jp,orcarouter.ai"), "site-inspector");
        Domain domain = service.startVerification(orgId, hostname, "http");

        Domain result = service.checkVerification(domain);

        assertEquals(Domain.STATUS_UNVERIFIED, result.getStatus());
    }

    @Test
    void nonSandboxedLoopbackTargetIsNeverFetched() throws IOException {
        // SSRF対策の確認: サンドボックス指定と異なるホスト名(127.0.0.1だが別ポート)を渡すと、
        // 実際のHTTP取得(このテストサーバーへの到達)が起きず、確認は失敗のままになる
        Long orgId = freshOrgId();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String hostname = "127.0.0.1:" + port;

        DomainSafetyChecker safetyChecker = new DomainSafetyChecker("127.0.0.1:1"); // 別ポートだけを許可
        DomainVerificationService service = new DomainVerificationService(domainRepository, safetyChecker, new HostnameDenylist(".go.jp,.lg.jp", "cyberace.co.jp,orcarouter.ai"), "site-inspector");

        Domain domain = service.startVerification(orgId, hostname, "http");
        server.createContext("/.well-known/site-inspector-verification.txt", exchange -> {
            byte[] bytes = domain.getVerificationToken().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        Domain result = service.checkVerification(domain);

        assertEquals(Domain.STATUS_UNVERIFIED, result.getStatus());
        assertNull(result.getVerifiedAt());
    }

    @Test
    void denylistedHostnameCannotStartVerification() {
        // L-4(指揮官指摘): 審査員・協賛企業のホスト名は、登録(所有確認の開始)自体を拒否する
        Long orgId = freshOrgId();
        DomainSafetyChecker safetyChecker = new DomainSafetyChecker("cyberace.co.jp");
        HostnameDenylist denylist = new HostnameDenylist(".go.jp,.lg.jp", "cyberace.co.jp,orcarouter.ai");
        DomainVerificationService service = new DomainVerificationService(domainRepository, safetyChecker, denylist, "site-inspector");

        assertThrows(DomainVerificationService.DeniedHostnameException.class,
                () -> service.startVerification(orgId, "cyberace.co.jp", "https"));
    }
}
