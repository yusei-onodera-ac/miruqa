package com.miruqa.landing;

import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

/** 組み込みTomcatで、JSP・サーブレット・フィルター・H2を、実際に動かして確かめる。 */
class SiteIntegrationTest {
    static DevServer server = new DevServer();
    static String base;
    static final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeAll static void start() throws Exception { System.setProperty("contact.enabled", "true"); base = "http://127.0.0.1:" + server.start(0); }
    @AfterAll static void stop() throws Exception { server.stop(); System.clearProperty("contact.enabled"); }

    static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> post(String path, Map<String, String> form) throws Exception {
        StringBuilder sb = new StringBuilder();
        form.forEach((k, v) -> {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString())).build(), HttpResponse.BodyHandlers.ofString());
    }
    static String csrf() throws Exception {
        Matcher m = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(get("/contact").body());
        assertTrue(m.find());
        return m.group(1);
    }
    static Map<String, String> validForm(String csrf) {
        Map<String, String> f = new HashMap<>();
        f.put("csrf", csrf); f.put("name", "山田 太郎"); f.put("company", "テスト"); f.put("email", "taro@example.com");
        f.put("category", "EARLY_ACCESS"); f.put("message", "先行案内を希望します。"); f.put("consent", "on");
        return f;
    }

    @Test void topPageRenders() throws Exception {
        var r = get("/");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("が画面を操作し"));
        assertTrue(r.body().contains("2026年10月末"));
        assertTrue(r.body().contains("仕様書が、そのままテストになる"), "特長がリポジトリから描画される");
        assertTrue(r.body().contains("よくあるご質問"));
    }
    @Test void securityHeaders() throws Exception {
        var h = get("/").headers();
        assertEquals("nosniff", h.firstValue("X-Content-Type-Options").orElse(""));
        assertEquals("DENY", h.firstValue("X-Frame-Options").orElse(""));
        assertTrue(h.firstValue("Content-Security-Policy").orElse("").contains("default-src 'self'"));
    }
    @Test void noInlineStyleOrScript() throws Exception {
        String body = get("/").body();
        assertFalse(body.contains(" style=\""), "CSPのため、インラインstyleは使わない");
        assertFalse(Pattern.compile("<script(?![^>]*src=)[^>]*>").matcher(body).find(), "インラインscriptは使わない");
    }
    @Test void staticAssets() throws Exception {
        assertEquals(200, get("/static/css/site.css").statusCode());
        assertEquals(200, get("/static/js/site.js").statusCode());
    }
    @Test void health() throws Exception {
        var r = get("/health");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("UP"));
    }
    @Test void notFoundUsesErrorPage() throws Exception {
        var r = get("/nope");
        assertEquals(404, r.statusCode());
        assertTrue(r.body().contains("ページを表示できませんでした"));
    }
    @Test void webInfIsNotServed() throws Exception { assertEquals(404, get("/WEB-INF/views/index.jsp").statusCode()); }

    @Test void contactSuccessRedirectsToThanks() throws Exception {
        var r = post("/contact", validForm(csrf()));
        assertEquals(302, r.statusCode());
        String loc = r.headers().firstValue("Location").orElse("");
        assertTrue(loc.contains("/contact/thanks?ref=MQ-"), loc);
        var t = get(loc.substring(loc.indexOf("/contact/thanks")));
        assertEquals(200, t.statusCode());
        assertTrue(t.body().contains("受付番号"));
    }
    @Test void contactWithoutCsrfIsForbidden() throws Exception {
        var f = validForm("x");
        f.remove("csrf");
        assertEquals(403, post("/contact", f).statusCode());
    }
    @Test void contactInvalidEmailShowsMessage() throws Exception {
        var f = validForm(csrf());
        f.put("email", "bad");
        var r = post("/contact", f);
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("メールアドレスの形式が正しくありません"));
        assertTrue(r.body().contains("山田 太郎"), "入力は保持される");
    }
    @Test void contactRequiresConsent() throws Exception {
        var f = validForm(csrf());
        f.remove("consent");
        assertEquals(400, post("/contact", f).statusCode());
    }
    @Test void xssInputIsEscaped() throws Exception {
        var f = validForm(csrf());
        f.put("email", "bad");
        f.put("name", "<script>alert(1)</script>");
        var r = post("/contact", f);
        assertFalse(r.body().contains("<script>alert(1)</script>"));
        assertTrue(r.body().contains("&lt;script&gt;"));
    }
    @Test void honeypotIsSilentlyIgnored() throws Exception {
        var f = validForm(csrf());
        f.put("website", "http://spam.example");
        var r = post("/contact", f);
        assertEquals(302, r.statusCode());
        assertTrue(r.headers().firstValue("Location").orElse("").contains("MQ-00000000"));
    }
    @Test void thanksRejectsBadReference() throws Exception {
        var r = get("/contact/thanks?ref=%3Cscript%3E");
        assertEquals(200, r.statusCode());
        assertFalse(r.body().contains("<script>"));
        assertFalse(r.body().contains("受付番号"));
    }

    @Test void specIsOptionalAndStated() throws Exception {
        String body = get("/").body();
        assertTrue(body.contains("仕様書がなくても"), "仕様書は任意であることを明記する");
    }
    @Test void noCookieIsSet() throws Exception {
        for (String path : new String[]{"/", "/contact", "/contact/privacy"}) {
            assertTrue(get(path).headers().allValues("Set-Cookie").isEmpty(), path + " はCookieを設定しない");
        }
    }
    @Test void privacyPolicyCitesLaws() throws Exception {
        String body = get("/contact/privacy").body();
        assertTrue(body.contains("第17条"));
        assertTrue(body.contains("第23条"));
        assertTrue(body.contains("第27条の12"));
        assertTrue(body.contains("要"), "未確認である旨と、記入箇所がある");
    }
    @Test void logoIsServed() throws Exception {
        assertEquals(200, get("/static/img/logo.png").statusCode());
        assertEquals(200, get("/static/img/favicon.png").statusCode());
    }
}
