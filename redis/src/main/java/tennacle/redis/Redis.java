package tennacle.redis;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** One connection pool and where its commands run: off the caller's thread, so the tick never waits on Redis. */
public final class Redis implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(Redis.class);

    private final URI uri;
    private final JedisPooled pool;
    private final ExecutorService io = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tennacle-redis-", 0).factory());

    private Redis(URI uri) {
        this.uri = uri;
        this.pool = new JedisPooled(uri);
    }

    /** {@code redis://[:password@]host:port}. A Redis not up yet is connected to once it is: until then commands fail. */
    public static @NotNull Redis connect(@NotNull String url) {
        Redis redis = new Redis(URI.create(url));
        try {
            redis.pool.ping();
        } catch (RuntimeException down) {
            LOGGER.error("Redis at {}:{} isn't reachable yet: presence and events across servers wait for it",
                    redis.uri.getHost(), redis.uri.getPort());
        }
        return redis;
    }

    URI uri() {
        return uri;
    }

    public <T> @NotNull CompletableFuture<T> run(@NotNull Function<JedisPooled, T> command) {
        return CompletableFuture.supplyAsync(() -> command.apply(pool), io);
    }

    /** On the caller's thread: for a caller already off the tick. */
    public <T> T now(@NotNull Function<JedisPooled, T> command) {
        return command.apply(pool);
    }

    @Override
    public void close() {
        io.shutdown();
        pool.close();
    }
}
