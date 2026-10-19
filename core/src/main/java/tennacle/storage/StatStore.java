package tennacle.storage;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongUnaryOperator;

/** Career numbers as rows, so they can be ranked. A stat is named by its vault key's id ({@code bedwars:career-kills}). */
public interface StatStore {

    record Standing(@NotNull UUID player, long value) {}

    /** 0 when the player has none. */
    @NotNull CompletableFuture<Long> get(@NotNull UUID player, @NotNull String stat);

    /** Atomic across servers. */
    @NotNull CompletableFuture<Long> update(@NotNull UUID player, @NotNull String stat, @NotNull LongUnaryOperator fn);

    /** Highest first. */
    @NotNull CompletableFuture<List<Standing>> top(@NotNull String stat, int limit);
}
