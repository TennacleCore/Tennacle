package tennacle.storage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Finished replays: their bytes and what they are. An index row over a bucket in production; {@link FileReplays}
 * until then. A game's spool is never here: it stays on the recording server's disk until the game ends.
 *
 * <p>Past the server's keep a replay is hidden from players, unless one bookmarked it: then it stays theirs to
 * watch. A staff hold (a report) keeps the bytes past the keep for staff alone.
 */
public interface ReplayStore {

    /** {@code playedAt} epoch millis of the end; {@code ticks} 0 when unknown. */
    record Entry(@NotNull String id, @NotNull String code, @NotNull String mode, @NotNull String map, long playedAt,
                 long ticks, long size) {}

    enum Bookmark { ADDED, REMOVED, FULL, MISSING }

    int BOOKMARKS = 25;

    @NotNull CompletableFuture<Void> put(@NotNull Entry entry, byte @NotNull [] bytes);

    /** Null when it was never kept, has expired, or is hidden from {@code viewer}. */
    @NotNull CompletableFuture<byte @Nullable []> bytes(@NotNull String id, @NotNull UUID viewer, boolean staff);

    /** Keeps the bytes for staff until {@code until}, whatever the keep says. */
    default @NotNull CompletableFuture<Void> hold(@NotNull String id, long until) {
        return CompletableFuture.completedFuture(null);
    }

    /** Toggles {@code player}'s bookmark on the replay, at most {@link #BOOKMARKS} each. */
    default @NotNull CompletableFuture<Bookmark> bookmark(@NotNull UUID player, @NotNull String id) {
        return CompletableFuture.completedFuture(Bookmark.MISSING);
    }
}
