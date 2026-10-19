package tennacle.net;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Requests a minute per key. Shared through Redis across API processes; {@link #local} within one. */
public interface RateLimiter {

    record Spent(boolean allowed, int remaining, long retryAfterSeconds) {}

    @NotNull Spent spend(@NotNull String key, int perMinute, long now);

    /** A token bucket per key, refilled evenly. */
    static @NotNull RateLimiter local() {
        final class Bucket {
            double tokens = -1;
            long at;
        }
        Map<String, Bucket> buckets = new ConcurrentHashMap<>();
        return (key, perMinute, now) -> {
            Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket());
            synchronized (bucket) {
                bucket.tokens = bucket.tokens < 0 ? perMinute
                        : Math.min(perMinute, bucket.tokens + (now - bucket.at) * perMinute / 60_000.0);
                bucket.at = now;
                if (bucket.tokens < 1) return new Spent(false, 0, Math.max(1, (long) Math.ceil((1 - bucket.tokens) * 60.0 / perMinute)));
                bucket.tokens -= 1;
                return new Spent(true, (int) bucket.tokens, 0);
            }
        };
    }
}
