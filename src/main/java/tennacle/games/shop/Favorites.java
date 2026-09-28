package tennacle.games.shop;

import tennacle.PlayerPrefs;

import io.github.term4.grotto.vault.VaultKey;
import net.minestom.server.codec.Codec;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Each player's pinned offers per storefront (Quick Buy); an empty id holds a place open. */
public final class Favorites {

    private static final VaultKey<Map<String, List<String>>> KEY =
            VaultKey.of("shop:favorites", Codec.STRING.mapValue(Codec.STRING.list()), Map.of());
    private static volatile PlayerPrefs.Entry<Map<String, List<String>>, Map<String, List<String>>> pinned;

    private Favorites() {}

    /** Registers the preference; at boot, before anyone logs in. */
    public static synchronized void install() {
        if (pinned == null) pinned = PlayerPrefs.register(KEY);
    }

    /** {@code player}'s pins at {@code front}, or null while they keep the storefront's own. */
    public static @Nullable List<String> of(@NotNull Player player, @NotNull String front) {
        Map<String, List<String>> all = pinned != null ? pinned.of(player.getUuid()) : null;
        return all != null ? all.get(front) : null;
    }

    public static void set(@NotNull Player player, @NotNull String front, @NotNull List<String> pins) {
        if (pinned == null) throw new IllegalStateException("Favorites.install() first");
        Map<String, List<String>> all = pinned.of(player.getUuid());
        Map<String, List<String>> next = new HashMap<>(all != null ? all : Map.of());
        next.put(front, List.copyOf(pins));
        pinned.set(player, Map.copyOf(next));
    }
}
