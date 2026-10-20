package tennacle.storage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Where finished matches are kept. A database table in production; {@link FileMatchStore} until then. */
public interface MatchStore {

    record Seat(@NotNull UUID id, @NotNull String name) {}

    /** {@code team} is Grotto's id, what a winner names; {@code name} as players read it, {@code color} a named text color. */
    record Side(@NotNull String team, @NotNull String name, @NotNull String color, @NotNull List<Seat> seats) {}

    /** {@code outcome} is Grotto's kind; {@code winner} a team id or null. Times in epoch millis. */
    record Match(@NotNull String id, @NotNull String code, @NotNull String mode, @NotNull String platform,
                 @NotNull String map, @NotNull String outcome, @Nullable String winner, long startedAt, long endedAt,
                 long ticks, @NotNull List<Side> sides) {}

    /** One player's number in one match: {@code bedwars:kills}. */
    record Line(@NotNull UUID player, @NotNull String stat, long value) {}

    /** A number between a player and {@code other}: another player's uuid, or a team id. */
    record Pair(@NotNull UUID player, @NotNull String other, @NotNull String stat, long value) {}

    @NotNull CompletableFuture<Void> add(@NotNull Match match);

    /** A match's per-player numbers, kept with it; separate from {@link #add} so no write waits on the other. */
    @NotNull CompletableFuture<Void> stats(@NotNull String match, @NotNull List<Line> lines, @NotNull List<Pair> pairs);

    /** Waits out pending writes; {@code false} = something was still in flight. */
    default boolean flush(long timeout, @NotNull TimeUnit unit) {
        return true;
    }
}
