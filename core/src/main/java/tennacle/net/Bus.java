package tennacle.net;

import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * Events between processes: every game server and the API service hear what any of them publish, themselves
 * included. Redis pub/sub when {@code tennacle.redis.url} is set; {@link LocalBus} within one process otherwise.
 * Delivery is at most once: a process that is down misses what was said meanwhile.
 */
public interface Bus {

    <T> void publish(@NotNull Topic<T> topic, @NotNull T event);

    /** {@code listener} runs off the tick thread. */
    <T> void subscribe(@NotNull Topic<T> topic, @NotNull Consumer<T> listener);

    default void close() {}
}
