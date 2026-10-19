package tennacle.net;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Who is online, and on which server. Redis when {@code tennacle.redis.url} is set, so every server and the API see
 * it; {@link LocalPresence} within one process otherwise. A server that dies takes its players with it within
 * {@link #STALE_SECONDS}.
 */
public interface Presence {

    int STALE_SECONDS = 90;

    /** {@code since} epoch millis of their join; {@code hidden} a vanished staff member. */
    record Here(@NotNull UUID player, @NotNull String name, @NotNull String server, long since, boolean hidden) {}

    @NotNull CompletableFuture<Void> joined(@NotNull Here here);

    @NotNull CompletableFuture<Void> left(@NotNull UUID player, @NotNull String server);

    /** Keeps {@code server} and its players fresh; the server calls it well inside {@link #STALE_SECONDS}. */
    default @NotNull CompletableFuture<Void> beat(@NotNull String server, @NotNull Iterable<Here> online) {
        return CompletableFuture.completedFuture(null);
    }

    @NotNull CompletableFuture<@Nullable Here> where(@NotNull UUID player);

    /** Players online, by server. */
    @NotNull CompletableFuture<Map<String, Integer>> servers();
}
