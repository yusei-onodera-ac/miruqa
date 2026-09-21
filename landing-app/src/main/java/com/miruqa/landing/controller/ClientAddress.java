package com.miruqa.landing.controller;

import jakarta.servlet.http.HttpServletRequest;

/** 接続元のIP。ALBの背後では、X-Forwarded-For の「最後の値」だけを信用する（ALBが末尾に、実際の接続元を付けるため）。 */
final class ClientAddress {
    private ClientAddress() {}

    static String of(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            return parts[parts.length - 1].trim();
        }
        return req.getRemoteAddr();
    }
}
