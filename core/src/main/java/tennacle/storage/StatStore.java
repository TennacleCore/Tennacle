package tennacle.storage;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongUnaryOperator;

/** Career numbers as rows, so they can be ranked. A stat is named by its vault key's id ({@code bedwars:kills}). */
public interface StatStore {

    record Standing(@NotNull UUID player, long value) {}

    /** 0 when the player has none. */
    @NotNull CompletableFuture<Long> get(@NotNull UUID player, @NotNull String stat);

    /** Atomic across servers. */
    @NotNull CompletableFuture<Long> update(@NotNull UUID player, @NotNull String stat, @NotNull LongUnaryOperator fn);

    /** Highest first. */
    @NotNull CompletableFuture<List<Standing>> top(@NotNull String stat, int limit);

    /** Every stat {@code player} has, by name; a store that can't list them answers none. */
    default @NotNull CompletableFuture<Map<String, Long>> all(@NotNull UUID player) {
        return CompletableFuture.completedFuture(Map.of());
    }

    /** Each stat += its delta: a finished game's totals, in one write where the store can. */
    default @NotNull CompletableFuture<Void> add(@NotNull UUID player, @NotNull Map<String, Long> deltas) {
        List<CompletableFuture<Long>> writes = new ArrayList<>();
        deltas.forEach((stat, delta) -> {
            if (delta != 0) writes.add(update(player, stat, old -> old + delta));
        });
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
    }
}
