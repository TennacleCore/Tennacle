package tennacle.redis;

import org.jetbrains.annotations.NotNull;
import redis.clients.jedis.JedisPooled;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** One connection pool and where its commands run: off the caller's thread, so the tick never waits on Redis. */
public final class Redis implements AutoCloseable {

    private final URI uri;
    private final JedisPooled pool;
    private final ExecutorService io = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tennacle-redis-", 0).factory());

    private Redis(URI uri) {
        this.uri = uri;
        this.pool = new JedisPooled(uri);
    }

    /** {@code redis://[:password@]host:port}; fails when the server can't be reached. */
    public static @NotNull Redis connect(@NotNull String url) {
        Redis redis = new Redis(URI.create(url));
        redis.pool.ping();
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
