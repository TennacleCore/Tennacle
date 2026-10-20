package tennacle.storage;

import io.github.term4.grotto.vault.Vault;
import io.github.term4.grotto.vault.VaultKey;
import net.minestom.server.codec.Codec;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongUnaryOperator;

/** Career numbers kept in a vault, under their own ids: the files' stand-in for the stats table. Nothing ranks. */
public final class VaultStats implements StatStore {

    private final Vault vault;
    private final Map<String, VaultKey<Integer>> keys = new ConcurrentHashMap<>();

    public VaultStats(@NotNull Vault vault) {
        this.vault = vault;
    }

    @Override
    public @NotNull CompletableFuture<Long> get(@NotNull UUID player, @NotNull String stat) {
        return vault.get(player, key(stat)).thenApply(Integer::longValue);
    }

    @Override
    public @NotNull CompletableFuture<Long> update(@NotNull UUID player, @NotNull String stat, @NotNull LongUnaryOperator fn) {
        return vault.update(player, key(stat), old -> Math.toIntExact(fn.applyAsLong(old))).thenApply(Integer::longValue);
    }

    @Override
    public @NotNull CompletableFuture<List<Standing>> top(@NotNull String stat, int limit) {
        return CompletableFuture.completedFuture(List.of());
    }

    private VaultKey<Integer> key(String stat) {
        return keys.computeIfAbsent(stat, id -> VaultKey.of(id, Codec.INT, 0));
    }
}
