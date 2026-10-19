package tennacle.api.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A token bucket per key: {@code perMinute} at most, refilled evenly. Per process: Redis once there are several. */
final class RateLimit {

    record Spent(boolean allowed, int remaining, long retryAfterSeconds) {}

    private static final class Bucket {
        double tokens;
        long at;
    }

    private final int perMinute;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    RateLimit(int perMinute) {
        this.perMinute = perMinute;
    }

    int perMinute() {
        return perMinute;
    }

    Spent spend(String key, long now) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> {
            Bucket fresh = new Bucket();
            fresh.tokens = perMinute;
            fresh.at = now;
            return fresh;
        });
        synchronized (bucket) {
            bucket.tokens = Math.min(perMinute, bucket.tokens + (now - bucket.at) * perMinute / 60_000.0);
            bucket.at = now;
            if (bucket.tokens < 1) {
                long wait = (long) Math.ceil((1 - bucket.tokens) * 60.0 / perMinute);
                return new Spent(false, 0, Math.max(1, wait));
            }
            bucket.tokens -= 1;
            return new Spent(true, (int) bucket.tokens, 0);
        }
    }
}
