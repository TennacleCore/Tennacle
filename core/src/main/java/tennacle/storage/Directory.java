package tennacle.storage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every player who has joined: the name they last used and when they were last here. A database in production;
 * {@link FileDirectory} until then.
 */
public interface Directory {

    /** {@code lastSeen}: epoch millis of their last join or leave. */
    record Entry(@NotNull UUID id, @NotNull String name, long lastSeen) {}

    @NotNull CompletableFuture<@Nullable Entry> byId(@NotNull UUID id);

    /** Any case; a name taken over by someone else answers with whoever used it last. */
    @NotNull CompletableFuture<@Nullable Entry> byName(@NotNull String name);

    @NotNull CompletableFuture<Void> seen(@NotNull UUID id, @NotNull String name, long at);

    /** The ones it knows among {@code ids}; a database answers in one query. */
    default @NotNull CompletableFuture<Map<UUID, Entry>> byIds(@NotNull Collection<UUID> ids) {
        Map<UUID, Entry> found = new ConcurrentHashMap<>();
        CompletableFuture<?>[] reads = ids.stream()
                .map(id -> byId(id).thenAccept(entry -> {
                    if (entry != null) found.put(id, entry);
                })).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(reads).thenApply(done -> found);
    }
}
