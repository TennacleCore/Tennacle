package tennacle.redis;

import org.jetbrains.annotations.NotNull;
import tennacle.net.RateLimiter;

/** A window per key per clock minute, counted in Redis so every API process spends from the same one. */
public final class RedisRateLimiter implements RateLimiter {

    private final Redis redis;

    public RedisRateLimiter(@NotNull Redis redis) {
        this.redis = redis;
    }

    @Override
    public @NotNull Spent spend(@NotNull String key, int perMinute, long now) {
        long minute = now / 60_000;
        String counter = "rate:" + key + ":" + minute;
        long used = redis.now(pool -> {
            long count = pool.incr(counter);
            if (count == 1) pool.expire(counter, 120);
            return count;
        });
        if (used > perMinute) return new Spent(false, 0, Math.max(1, (minute + 1) * 60 - now / 1000));
        return new Spent(true, (int) (perMinute - used), 0);
    }
}
