package tennacle;


import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import net.kyori.adventure.text.Component;
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
 *
 * <p>The configuration event WAITS for the fetch (it runs on the login thread, not the tick loop), because a
 * miss is indistinguishable from an empty account downstream: a kit editor that reads null writes back a map
 * holding only the kit it just edited. A player whose data cannot be read is refused, not defaulted.
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

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerPrefs.class);
    private static final long LOAD_TIMEOUT_SECONDS = 10;

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
            List<CompletableFuture<?>> loading = new ArrayList<>();
            for (Entry<?, ?> entry : entries) loading.add(fetch(entry, player));
            try {
                CompletableFuture.allOf(loading.toArray(CompletableFuture[]::new))
                        .get(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception failed) {
                LOGGER.error("player data did not load for {}", e.getPlayer().getUsername(), failed);
                for (Entry<?, ?> entry : entries) entry.cache.remove(player);
                e.getPlayer().kick(Component.text("Could not load your data - try again in a moment"));
            }
        });
        node.addListener(PlayerDisconnectEvent.class, e -> {
            for (Entry<?, ?> entry : entries) entry.cache.remove(e.getPlayer().getUuid());
        });
    }

    private static <S, T> CompletableFuture<?> fetch(Entry<S, T> entry, UUID player) {
        return Matches.grotto().vault().get(player, entry.key)
                .thenAccept(stored -> {
                    // a read landing after the timeout kick would otherwise refill the cache the kick cleared
                    if (MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(player) == null) return;
                    entry.cache.put(player, entry.decode.apply(stored));
                });
    }
}
