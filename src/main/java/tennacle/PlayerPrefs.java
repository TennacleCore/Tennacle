package tennacle;


import tennacle.games.Matches;

import io.github.term4.grotto.vault.VaultKey;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * The one login-prefetch pipeline for per-player vault data: a consumer registers its key once, the value
 * is fetched at configuration and cached until disconnect, and every later read is synchronous - a game
 * start never races an async lookup. Swapping the storage backend later touches the vault, not consumers.
 */
public final class PlayerPrefs {

    /** One registered preference: reads are cached ({@code null} until the login fetch lands). */
    public static final class Entry<S, T> {
        private final VaultKey<S> key;
        private final Function<S, T> decode;
        private final Function<T, S> encode;
        private final Map<UUID, T> cache = new ConcurrentHashMap<>();

        private Entry(VaultKey<S> key, Function<S, T> decode, Function<T, S> encode) {
            this.key = key;
            this.decode = decode;
            this.encode = encode;
        }

        public @Nullable T of(@NotNull UUID player) {
            return cache.get(player);
        }

        public void set(@NotNull Player player, @NotNull T value) {
            cache.put(player.getUuid(), value);
            Matches.grotto().vault().set(player.getUuid(), key, encode.apply(value));
        }
    }

    private static final List<Entry<?, ?>> entries = new CopyOnWriteArrayList<>();
    private static volatile boolean installed;

    private PlayerPrefs() {}

    public static <S> Entry<S, S> register(@NotNull VaultKey<S> key) {
        return register(key, Function.identity(), Function.identity());
    }

    public static <S, T> Entry<S, T> register(@NotNull VaultKey<S> key,
                                              @NotNull Function<S, T> decode, @NotNull Function<T, S> encode) {
        Entry<S, T> entry = new Entry<>(key, decode, encode);
        entries.add(entry);
        return entry;
    }

    /** Wire the login fetch + disconnect eviction; once, at boot. */
    public static void install() {
        if (installed) return;
        installed = true;
        var node = MinecraftServer.getGlobalEventHandler();
        node.addListener(AsyncPlayerConfigurationEvent.class, e -> {
            UUID player = e.getPlayer().getUuid();
            for (Entry<?, ?> entry : entries) fetch(entry, player);
        });
        node.addListener(PlayerDisconnectEvent.class, e -> {
            for (Entry<?, ?> entry : entries) entry.cache.remove(e.getPlayer().getUuid());
        });
    }

    private static <S, T> void fetch(Entry<S, T> entry, UUID player) {
        Matches.grotto().vault().get(player, entry.key)
                .thenAccept(stored -> entry.cache.put(player, entry.decode.apply(stored)));
    }
}
