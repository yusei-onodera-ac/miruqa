package com.miruqa.landing.service;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/** 1つのキー（IPのハッシュ）あたり、1時間の送信件数を制限する（1台のメモリ内。複数台では、ALB側のWAFと併用）。 */
public class RateLimiter {
    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public RateLimiter(int limit, long windowMillis, Clock clock) {
        this.limit = limit; this.windowMillis = windowMillis; this.clock = clock;
    }

    /** 許可されれば true（同時に、1件を記録する）。 */
    public boolean tryAcquire(String key) {
        long now = clock.millis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > windowMillis) q.pollFirst();
            if (q.size() >= limit) return false;
            q.addLast(now);
            return true;
        }
    }
}
