package tennacle.storage;

import io.github.term4.grotto.vault.VaultKey;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The vault keys that are career numbers: a {@link StatRouting} vault keeps these in the {@link StatStore}. */
public final class StatKeys {

    private static final Map<String, VaultKey<Integer>> keys = new ConcurrentHashMap<>();

    private StatKeys() {}

    public static void register(@NotNull VaultKey<Integer> key) {
        keys.put(key.id(), key);
    }

    public static boolean is(@NotNull String id) {
        return keys.containsKey(id);
    }

    public static @NotNull Collection<VaultKey<Integer>> all() {
        return List.copyOf(keys.values());
    }
}
