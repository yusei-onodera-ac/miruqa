package com.miruqa.landing;

import com.miruqa.landing.entity.Inquiry;
import com.miruqa.landing.repository.InquiryRepository;
import com.miruqa.landing.service.*;
import com.miruqa.landing.value.InvalidValueException;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InquiryServiceTest {
    /** メモリ上の代替（リポジトリの契約だけを確かめる）。 */
    static class FakeRepo implements InquiryRepository {
        final List<Inquiry> saved = new ArrayList<>();
        public Inquiry save(Inquiry i) { Inquiry s = i.withId(saved.size() + 1L); saved.add(s); return s; }
        public Optional<Inquiry> findByReferenceCode(String c) { return saved.stream().filter(i -> i.referenceCode().equals(c)).findFirst(); }
        public long countByEmailSince(String e, Instant since) { return saved.stream().filter(i -> i.email().value().equals(e) && !i.createdAt().isBefore(since)).count(); }
        public void ping() {}
    }

    final Clock clock = Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), ZoneOffset.UTC);
    FakeRepo repo = new FakeRepo();

    InquiryService service(int limit) { return new InquiryService(repo, new RateLimiter(limit, 3_600_000L, clock), clock, "salt"); }

    InquiryService.Request req(String email, boolean consent) {
        return new InquiryService.Request("山田 太郎", "株式会社テスト", email, "EARLY_ACCESS", "先行案内を希望します。", consent, "203.0.113.5");
    }

    @Test void savesAndReturnsReference() {
        var r = service(5).submit(req("a@example.com", true));
        assertTrue(r.referenceCode().matches("^MQ-[A-Z0-9]{8}$"));
        assertEquals(1, repo.saved.size());
    }
    @Test void ipIsHashedNotStored() {
        service(5).submit(req("a@example.com", true));
        String stored = repo.saved.get(0).ipHash();
        assertFalse(stored.contains("203.0.113.5"));
        assertEquals(64, stored.length());
    }
    @Test void consentIsRequired() {
        assertThrows(InvalidValueException.class, () -> service(5).submit(req("a@example.com", false)));
        assertTrue(repo.saved.isEmpty());
    }
    @Test void invalidInputIsRejected() {
        assertThrows(InvalidValueException.class, () -> service(5).submit(req("bad", true)));
    }
    @Test void rateLimitPerAddress() {
        var s = service(2);
        s.submit(req("a@example.com", true));
        s.submit(req("b@example.com", true));
        assertThrows(InquiryService.RateLimitedException.class, () -> s.submit(req("c@example.com", true)));
    }
    @Test void rateLimitPerEmail() {
        var s = service(100);
        for (int i = 0; i < 3; i++) s.submit(req("a@example.com", true));
        assertThrows(InquiryService.RateLimitedException.class, () -> s.submit(req("a@example.com", true)));
    }
}
