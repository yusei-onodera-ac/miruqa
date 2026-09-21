package ai.hack2026.web.util;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 画面表示用のフォーマット(第3a章: 機械の形式(ISO8601・UTC)のまま出さない)。
 * ワーカーのrun.jsonはUTCのISO8601文字列(例: 2026-09-20T19:53:18+00:00)を返すため、
 * 日本時間(Asia/Tokyo)に変換してから "yyyy-MM-dd HH:mm" で表示する。
 */
public final class DisplayFormat {

    private static final DateTimeFormatter JST_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Tokyo"));

    private DisplayFormat() {
    }

    public static String jst(Object isoTimestamp) {
        if (isoTimestamp == null) {
            return "-";
        }
        try {
            Instant instant = OffsetDateTime.parse(isoTimestamp.toString()).toInstant();
            return JST_FORMAT.format(instant);
        } catch (RuntimeException e) {
            return "-";
        }
    }

    /** ソート用。パースできなければ最古扱い(Instant.MIN)にして末尾に回す。 */
    public static Instant instantOf(Object isoTimestamp) {
        if (isoTimestamp == null) {
            return Instant.MIN;
        }
        try {
            return OffsetDateTime.parse(isoTimestamp.toString()).toInstant();
        } catch (RuntimeException e) {
            return Instant.MIN;
        }
    }

    public static String duration(Object seconds) {
        if (!(seconds instanceof Number)) {
            return "-";
        }
        long total = Math.round(((Number) seconds).doubleValue());
        long m = total / 60;
        long s = total % 60;
        return m > 0 ? m + "分" + s + "秒" : s + "秒";
    }

    /** run(ワーカーのrun.jsonをMapにしたもの)からmetrics.costUsd.totalを取り出して表示用にする。 */
    public static String costOf(Map<?, ?> run) {
        Object total = numberAt(run, "metrics", "costUsd", "total");
        return total instanceof Number n ? String.format("$%.4f", n.doubleValue()) : "-";
    }

    public static String durationOf(Map<?, ?> run) {
        return duration(numberAt(run, "metrics", "durationSec"));
    }

    private static Object numberAt(Map<?, ?> root, String... path) {
        Object cur = root;
        for (String key : path) {
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = m.get(key);
        }
        return cur;
    }
}
