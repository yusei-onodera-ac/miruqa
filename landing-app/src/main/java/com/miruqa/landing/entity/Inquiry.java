package com.miruqa.landing.entity;

import com.miruqa.landing.value.*;
import java.time.Instant;

/** お問い合わせ・先行案内の申込み（保存の単位）。個人情報を含むため、IPアドレスは、ハッシュだけを保持する。 */
public record Inquiry(
        Long id,
        String referenceCode,
        PersonName name,
        CompanyName company,
        Email email,
        InquiryCategory category,
        MessageText message,
        boolean consented,
        String ipHash,
        Instant createdAt) {

    public Inquiry withId(long newId) {
        return new Inquiry(newId, referenceCode, name, company, email, category, message, consented, ipHash, createdAt);
    }
}
