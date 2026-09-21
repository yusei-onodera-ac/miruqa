package com.miruqa.landing;

import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

/** 運営者情報の記入が済むまで、個人情報を取得するフォームを止めた状態（既定）で、公開できることを確かめる。 */
class ContactDisabledTest {
    static DevServer server = new DevServer();
    static String base;
    static final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeAll static void start() throws Exception {
        System.clearProperty("contact.enabled");
        base = "http://127.0.0.1:" + server.start(0);
    }
    @AfterAll static void stop() throws Exception { server.stop(); }

    static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void topPageShowsPreparingInsteadOfForm() throws Exception {
        String body = get("/").body();
        assertTrue(body.contains("先行案内は、準備中です"));
        assertFalse(body.contains("/contact"), "フォームへのリンクを出さない");
    }
    @Test void contactPagesAreNotFound() throws Exception {
        assertEquals(404, get("/contact").statusCode());
        assertEquals(404, get("/contact/privacy").statusCode());
        assertEquals(404, get("/contact/thanks?ref=MQ-AAAAAAAA").statusCode());
    }
    @Test void postIsRejected() throws Exception {
        var r = http.send(HttpRequest.newBuilder(URI.create(base + "/contact")).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("name=a")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, r.statusCode());
    }
    @Test void healthIsUpWithoutDatabase() throws Exception {
        var r = get("/health");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("UP"));
    }
    @Test void noCookieOnTopPage() throws Exception {
        assertTrue(get("/").headers().allValues("Set-Cookie").isEmpty());
    }
}
