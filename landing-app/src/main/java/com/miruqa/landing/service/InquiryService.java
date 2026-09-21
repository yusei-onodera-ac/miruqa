package com.miruqa.landing.service;

import com.miruqa.landing.entity.Inquiry;
import com.miruqa.landing.repository.InquiryRepository;
import com.miruqa.landing.value.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/** お問い合わせの受付。入力の検証（値オブジェクト）、同意の確認、送信回数の制限、保存を行う。 */
public class InquiryService {
    private static final char[] CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final InquiryRepository repository;
    private final RateLimiter rateLimiter;
    private final Clock clock;
    private final String ipSalt;
    private final SecureRandom random = new SecureRandom();

    public InquiryService(InquiryRepository repository, RateLimiter rateLimiter, Clock clock, String ipSalt) {
        this.repository = repository; this.rateLimiter = rateLimiter; this.clock = clock; this.ipSalt = ipSalt;
    }

    /** 送信の要求（画面の入力そのまま）。 */
    public record Request(String name, String company, String email, String category, String message,
                          boolean consented, String remoteAddress) {}

    /** 受付の結果。 */
    public record Receipt(String referenceCode) {}

    public static class RateLimitedException extends RuntimeException {
        public RateLimitedException() { super("短時間に多くの送信がありました。しばらくしてから、もう一度お試しください。"); }
    }

    public Receipt submit(Request req) {
        PersonName name = new PersonName(req.name());
        CompanyName company = new CompanyName(req.company());
        Email email = new Email(req.email());
        InquiryCategory category = InquiryCategory.parse(req.category());
        MessageText message = new MessageText(req.message());
        if (!req.consented()) throw new InvalidValueException("プライバシーポリシーへの同意が必要です。");

        String ipHash = hashIp(req.remoteAddress());
        if (!rateLimiter.tryAcquire(ipHash)) throw new RateLimitedException();
        Instant now = clock.instant();
        // 同じメールアドレスからの連続送信（1時間に3件まで）
        if (repository.countByEmailSince(email.value(), now.minus(Duration.ofHours(1))) >= 3) throw new RateLimitedException();

        Inquiry saved = repository.save(new Inquiry(null, newReferenceCode(), name, company, email, category, message, true, ipHash, now));
        return new Receipt(saved.referenceCode());
    }

    public Optional<Inquiry> find(String referenceCode) { return repository.findByReferenceCode(referenceCode); }

    String hashIp(String remoteAddress) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((ipSalt + ":" + (remoteAddress == null ? "" : remoteAddress)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String newReferenceCode() {
        StringBuilder sb = new StringBuilder("MQ-");
        for (int i = 0; i < 8; i++) sb.append(CODE_CHARS[random.nextInt(CODE_CHARS.length)]);
        return sb.toString();
    }
}
