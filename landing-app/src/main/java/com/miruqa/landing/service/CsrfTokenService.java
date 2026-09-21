package com.miruqa.landing.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

/** 署名つきのCSRFトークン（セッションを使わないため、複数台でも動く）。形式: 発行時刻.乱数.署名。有効期限つき。 */
public class CsrfTokenService {
    private static final long TTL_MILLIS = 2 * 60 * 60 * 1000L;
    private final byte[] key;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public CsrfTokenService(String secret, Clock clock) {
        byte[] k;
        if (secret == null || secret.isBlank()) { k = new byte[32]; random.nextBytes(k); }
        else k = secret.getBytes(StandardCharsets.UTF_8);
        this.key = k; this.clock = clock;
    }

    public String issue() {
        byte[] r = new byte[12]; random.nextBytes(r);
        String body = clock.millis() + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(r);
        return body + "." + sign(body);
    }

    public boolean verify(String token) {
        if (token == null) return false;
        int last = token.lastIndexOf('.');
        int first = token.indexOf('.');
        if (last <= first || first < 0) return false;
        String body = token.substring(0, last);
        String sig = token.substring(last + 1);
        if (!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8))) return false;
        try {
            long issued = Long.parseLong(body.substring(0, first));
            long age = clock.millis() - issued;
            return age >= 0 && age <= TTL_MILLIS;
        } catch (NumberFormatException e) { return false; }
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
