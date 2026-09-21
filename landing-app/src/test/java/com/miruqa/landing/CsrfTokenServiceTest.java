package com.miruqa.landing;

import com.miruqa.landing.service.CsrfTokenService;
import org.junit.jupiter.api.Test;

import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class CsrfTokenServiceTest {
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-22T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return now; }
    }

    @Test void validToken() {
        var s = new CsrfTokenService("secret", new MutableClock());
        assertTrue(s.verify(s.issue()));
    }
    @Test void tamperedTokenIsRejected() {
        var s = new CsrfTokenService("secret", new MutableClock());
        String t = s.issue();
        assertFalse(s.verify(t + "x"));
        assertFalse(s.verify("1." + t.substring(t.indexOf('.') + 1)));
        assertFalse(s.verify(null));
        assertFalse(s.verify(""));
        assertFalse(s.verify("a.b"));
    }
    @Test void otherSecretIsRejected() {
        var c = new MutableClock();
        assertFalse(new CsrfTokenService("other", c).verify(new CsrfTokenService("secret", c).issue()));
    }
    @Test void expires() {
        var c = new MutableClock();
        var s = new CsrfTokenService("secret", c);
        String t = s.issue();
        c.now = c.now.plus(Duration.ofHours(3));
        assertFalse(s.verify(t));
    }
}
