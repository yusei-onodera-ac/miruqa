package com.miruqa.landing;

import com.miruqa.landing.entity.Inquiry;
import com.miruqa.landing.repository.JdbcInquiryRepository;
import com.miruqa.landing.value.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class JdbcInquiryRepositoryTest {
    JdbcDataSource ds;
    JdbcInquiryRepository repo;

    @BeforeEach void setUp() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:repo" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        String sql = new String(getClass().getResourceAsStream("/schema.sql").readAllBytes(), StandardCharsets.UTF_8);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String s : sql.split(";")) if (!s.isBlank()) st.execute(s);
        }
        repo = new JdbcInquiryRepository(ds);
    }

    Inquiry sample(String code, String email) {
        return new Inquiry(null, code, new PersonName("山田"), new CompanyName(""), new Email(email),
                InquiryCategory.DEMO, new MessageText("こんにちは"), true, "h".repeat(64), Instant.now());
    }

    @Test void saveAndFind() {
        Inquiry saved = repo.save(sample("MQ-AAAAAAAA", "a@example.com"));
        assertNotNull(saved.id());
        Inquiry found = repo.findByReferenceCode("MQ-AAAAAAAA").orElseThrow();
        assertEquals("山田", found.name().value());
        assertEquals(InquiryCategory.DEMO, found.category());
    }
    @Test void notFound() { assertTrue(repo.findByReferenceCode("MQ-ZZZZZZZZ").isEmpty()); }
    @Test void countsByEmail() {
        repo.save(sample("MQ-BBBBBBBB", "a@example.com"));
        repo.save(sample("MQ-CCCCCCCC", "a@example.com"));
        repo.save(sample("MQ-DDDDDDDD", "b@example.com"));
        assertEquals(2, repo.countByEmailSince("a@example.com", Instant.now().minusSeconds(60)));
    }
    @Test void sqlInjectionIsHarmless() {
        repo.save(sample("MQ-EEEEEEEE", "a@example.com"));
        assertEquals(0, repo.countByEmailSince("x' OR '1'='1", Instant.now().minusSeconds(60)));
        assertTrue(repo.findByReferenceCode("' OR 1=1 --").isEmpty());
    }
    @Test void duplicateReferenceCodeFails() {
        repo.save(sample("MQ-FFFFFFFF", "a@example.com"));
        assertThrows(RuntimeException.class, () -> repo.save(sample("MQ-FFFFFFFF", "b@example.com")));
    }
    @Test void pingWorks() { repo.ping(); }
}
