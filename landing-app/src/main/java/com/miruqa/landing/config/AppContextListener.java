package com.miruqa.landing.config;

import com.miruqa.landing.repository.*;
import com.miruqa.landing.service.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;

/** 起動時に、接続プール・テーブル・各層の部品を組み立てて、ServletContextに置く（DIコンテナは使わない）。 */
@WebListener
public class AppContextListener implements ServletContextListener {
    public static final String SERVICES = "miruqa.services";
    private HikariDataSource dataSource;

    /** 各層の部品の束。 */
    public record Services(LandingService landing, InquiryService inquiry, CsrfTokenService csrf, InquiryRepository inquiries) {}

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        ServletContext ctx = sce.getServletContext();
        Clock clock = Clock.systemUTC();
        // フォームが停止中（既定）は、個人情報を保存しないため、DBを使わない（接続もしない）。
        InquiryRepository repo = null;
        InquiryService inquiryService = null;
        if (AppConfig.contactEnabled()) {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(AppConfig.jdbcUrl());
            cfg.setUsername(AppConfig.dbUser());
            cfg.setPassword(AppConfig.dbPassword());
            cfg.setMaximumPoolSize(AppConfig.dbPoolSize());
            cfg.setConnectionTimeout(5000);
            dataSource = new HikariDataSource(cfg);
            initSchema();
            repo = new JdbcInquiryRepository(dataSource);
            inquiryService = new InquiryService(repo, new RateLimiter(AppConfig.rateLimitPerHour(), 3_600_000L, clock), clock, AppConfig.ipSalt());
        }
        Services s = new Services(
                new LandingService(new StaticContentRepository()),
                inquiryService,
                new CsrfTokenService(AppConfig.csrfSecret(), clock),
                repo);
        ctx.setAttribute(SERVICES, s);
    }

    private void initSchema() {
        try (InputStream in = getClass().getResourceAsStream("/schema.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                for (String stmt : sql.split(";")) if (!stmt.isBlank()) st.execute(stmt);
            }
        } catch (Exception e) {
            throw new IllegalStateException("スキーマの初期化に失敗しました", e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        if (dataSource != null) dataSource.close();
    }

    public static Services services(ServletContext ctx) { return (Services) ctx.getAttribute(SERVICES); }
}
