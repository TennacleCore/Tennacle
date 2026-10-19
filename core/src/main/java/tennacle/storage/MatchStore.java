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

    /** {@code outcome} is Grotto's kind; {@code winner} a team id or null. Times in epoch millis. */
    record Match(@NotNull String id, @NotNull String code, @NotNull String mode, @NotNull String platform,
                 @NotNull String outcome, @Nullable String winner, long startedAt, long endedAt, long ticks,
                 @NotNull List<List<Seat>> sides) {}

    @NotNull CompletableFuture<Void> add(@NotNull Match match);

    /** Waits out pending writes; {@code false} = something was still in flight. */
    default boolean flush(long timeout, @NotNull TimeUnit unit) {
        return true;
    }
}
