package com.miruqa.landing.repository;

import com.miruqa.landing.entity.Inquiry;
import com.miruqa.landing.value.*;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.Optional;

/** JDBCによる実装（PostgreSQL・H2の両方で動く標準SQLだけを使う）。SQLは、すべて、パラメータ化している。 */
public class JdbcInquiryRepository implements InquiryRepository {
    private final DataSource dataSource;

    public JdbcInquiryRepository(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public Inquiry save(Inquiry i) {
        String sql = "INSERT INTO inquiries (reference_code, name, company, email, category, message, consented, ip_hash, created_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, i.referenceCode());
            ps.setString(2, i.name().value());
            ps.setString(3, i.company().value());
            ps.setString(4, i.email().value());
            ps.setString(5, i.category().name());
            ps.setString(6, i.message().value());
            ps.setBoolean(7, i.consented());
            ps.setString(8, i.ipHash());
            ps.setTimestamp(9, Timestamp.from(i.createdAt()));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return i.withId(keys.getLong(1));
            }
            return i;
        } catch (SQLException e) {
            throw new RepositoryException("お問い合わせの保存に失敗しました", e);
        }
    }

    @Override
    public Optional<Inquiry> findByReferenceCode(String code) {
        String sql = "SELECT id, reference_code, name, company, email, category, message, consented, ip_hash, created_at "
                + "FROM inquiries WHERE reference_code = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Inquiry(
                        rs.getLong("id"), rs.getString("reference_code"),
                        new PersonName(rs.getString("name")), new CompanyName(rs.getString("company")),
                        new Email(rs.getString("email")), InquiryCategory.valueOf(rs.getString("category")),
                        new MessageText(rs.getString("message")), rs.getBoolean("consented"),
                        rs.getString("ip_hash"), rs.getTimestamp("created_at").toInstant()));
            }
        } catch (SQLException e) {
            throw new RepositoryException("お問い合わせの取得に失敗しました", e);
        }
    }

    @Override
    public long countByEmailSince(String email, Instant since) {
        String sql = "SELECT COUNT(*) FROM inquiries WHERE email = ? AND created_at >= ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, email);
            ps.setTimestamp(2, Timestamp.from(since));
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        } catch (SQLException e) {
            throw new RepositoryException("件数の取得に失敗しました", e);
        }
    }

    @Override
    public void ping() {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("SELECT 1");
        } catch (SQLException e) {
            throw new RepositoryException("データベースに接続できません", e);
        }
    }
}
