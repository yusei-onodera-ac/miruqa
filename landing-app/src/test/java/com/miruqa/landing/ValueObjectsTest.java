package com.miruqa.landing;

import com.miruqa.landing.value.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ValueObjectsTest {
    @Test void emailIsNormalized() { assertEquals("a@example.com", new Email("  A@Example.COM ").value()); }
    @Test void emailMasked() { assertEquals("a***@example.com", new Email("abc@example.com").masked()); }
    @Test void invalidEmails() {
        for (String bad : new String[]{"", "  ", "abc", "a@b", "a b@c.com", "@x.com", "a@@x.com"})
            assertThrows(InvalidValueException.class, () -> new Email(bad), bad);
        assertThrows(InvalidValueException.class, () -> new Email(null));
    }
    @Test void nameRules() {
        assertEquals("山田 太郎", new PersonName(" 山田 太郎 ").value());
        assertThrows(InvalidValueException.class, () -> new PersonName(" "));
        assertThrows(InvalidValueException.class, () -> new PersonName("あ".repeat(61)));
    }
    @Test void controlCharactersAreRemoved() {
        assertEquals("ab", new PersonName("a" + (char) 0 + "b").value());
        assertEquals("a" + (char) 10 + "b", new MessageText("a" + (char) 13 + (char) 10 + "b").value());
    }
    @Test void companyIsOptional() {
        assertTrue(new CompanyName(null).isEmpty());
        assertThrows(InvalidValueException.class, () -> new CompanyName("a".repeat(101)));
    }
    @Test void messageRules() {
        assertThrows(InvalidValueException.class, () -> new MessageText(""));
        assertThrows(InvalidValueException.class, () -> new MessageText("あ".repeat(2001)));
        assertEquals(2000, new MessageText("あ".repeat(2000)).value().length());
    }
    @Test void categoryParse() {
        assertEquals(InquiryCategory.DEMO, InquiryCategory.parse("DEMO"));
        assertThrows(InvalidValueException.class, () -> InquiryCategory.parse("x"));
        assertThrows(InvalidValueException.class, () -> InquiryCategory.parse(null));
    }
}
