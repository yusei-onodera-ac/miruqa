package com.miruqa.landing.config;

/** 設定は環境変数から読む（AWSでは、ECSのタスク定義／Secrets Managerで渡す）。秘密の値はコードに持たない。 */
public final class AppConfig {
    private AppConfig() {}

    public static String env(String key, String defaultValue) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }

    public static String jdbcUrl() { return env("DB_URL", "jdbc:h2:mem:landing;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"); }
    public static String dbUser() { return env("DB_USER", "sa"); }
    public static String dbPassword() { return env("DB_PASSWORD", ""); }
    public static int dbPoolSize() { return Integer.parseInt(env("DB_POOL_SIZE", "5")); }
    /** CSRFトークンの署名鍵。複数台で共通にするため、本番は、環境変数で渡す（未設定なら起動ごとに生成）。 */
    public static String csrfSecret() { return env("CSRF_SECRET", ""); }
    /** お問い合わせ・先行案内フォームの有効/無効。個人情報を取得するため、運営者情報の記入が済むまで、既定は無効。 */
    public static boolean contactEnabled() {
        return "true".equalsIgnoreCase(env("CONTACT_ENABLED", System.getProperty("contact.enabled", "false")));
    }
    public static String siteUrl() { return env("SITE_URL", "https://miruqa.com"); }
    /** 1つのIPが、1時間に送れる件数。 */
    public static int rateLimitPerHour() { return Integer.parseInt(env("INQUIRY_RATE_LIMIT_PER_HOUR", "5")); }
    /** IPのハッシュに使う塩。 */
    public static String ipSalt() { return env("IP_HASH_SALT", "miruqa-landing"); }
}
