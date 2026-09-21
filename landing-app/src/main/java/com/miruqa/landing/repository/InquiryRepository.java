package com.miruqa.landing.repository;

import com.miruqa.landing.entity.Inquiry;
import java.util.Optional;

/** お問い合わせの保存先。本番はPostgreSQL（RDS）、テストはH2。 */
public interface InquiryRepository {
    Inquiry save(Inquiry inquiry);
    Optional<Inquiry> findByReferenceCode(String referenceCode);
    long countByEmailSince(String email, java.time.Instant since);
    /** 疎通確認用（ヘルスチェック）。 */
    void ping();
}
