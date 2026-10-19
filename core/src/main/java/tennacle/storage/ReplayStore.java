package tennacle.storage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Finished replays: their bytes and what they are. An index row over a bucket in production; {@link FileReplays}
 * until then. A game's spool is never here: it stays on the recording server's disk until the game ends.
 */
public interface ReplayStore {

    /** {@code playedAt} epoch millis of the end; {@code ticks} 0 when unknown. */
    record Entry(@NotNull String id, @NotNull String code, @NotNull String mode, @NotNull String map, long playedAt,
                 long ticks, long size) {}

    @NotNull CompletableFuture<Void> put(@NotNull Entry entry, byte @NotNull [] bytes);

    /** Null when it was never kept or has expired. */
    @NotNull CompletableFuture<byte @Nullable []> bytes(@NotNull String id);

    /** Keeps the replay at least until {@code until}: a bookmark or a report holds it past its usual life. */
    default @NotNull CompletableFuture<Void> keep(@NotNull String id, long until) {
        return CompletableFuture.completedFuture(null);
    }
}
