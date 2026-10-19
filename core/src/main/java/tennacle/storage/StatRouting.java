package tennacle.storage;

import io.github.term4.grotto.vault.Vault;
import io.github.term4.grotto.vault.VaultKey;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

/** A vault that keeps {@link StatKeys} in the {@link StatStore} and everything else in {@code rest}. */
public final class StatRouting implements Vault {

    private final Vault rest;
    private final StatStore stats;

    public StatRouting(@NotNull Vault rest, @NotNull StatStore stats) {
        this.rest = rest;
        this.stats = stats;
    }

    @Override
    @SuppressWarnings("unchecked") // StatKeys only holds VaultKey<Integer>
    public <T> @NotNull CompletableFuture<T> get(@NotNull UUID player, @NotNull VaultKey<T> key) {
        if (!StatKeys.is(key.id())) return rest.get(player, key);
        return stats.get(player, key.id()).thenApply(value -> (T) Integer.valueOf(Math.toIntExact(value)));
    }

    @Override
    public <T> @NotNull CompletableFuture<Void> set(@NotNull UUID player, @NotNull VaultKey<T> key, @NotNull T value) {
        if (!StatKeys.is(key.id())) return rest.set(player, key, value);
        long to = ((Integer) value).longValue();
        return stats.update(player, key.id(), old -> to).thenApply(stored -> null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> @NotNull CompletableFuture<T> update(@NotNull UUID player, @NotNull VaultKey<T> key,
                                                    @NotNull UnaryOperator<T> fn) {
        if (!StatKeys.is(key.id())) return rest.update(player, key, fn);
        UnaryOperator<Integer> ints = (UnaryOperator<Integer>) fn;
        return stats.update(player, key.id(), old -> ints.apply(Math.toIntExact(old)))
                .thenApply(stored -> (T) Integer.valueOf(Math.toIntExact(stored)));
    }
}
