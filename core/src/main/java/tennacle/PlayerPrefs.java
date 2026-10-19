package tennacle;


import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import net.kyori.adventure.text.Component;

import io.github.term4.grotto.vault.Vault;
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
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

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
            vault.get().set(player.getUuid(), key, encode.apply(value));
        }

        /** The cached value for someone online, else the stored one. */
        public @NotNull CompletableFuture<T> load(@NotNull UUID player) {
            T cached = cache.get(player);
            if (cached != null) return CompletableFuture.completedFuture(cached);
            return vault.get().get(player, key).thenApply(decode);
        }

        /** The stored value read again into an online player's cache: another process changed it. */
        public @NotNull CompletableFuture<T> refresh(@NotNull UUID player) {
            return vault.get().get(player, key).thenApply(stored -> {
                T value = decode.apply(stored);
                if (MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(player) != null) cache.put(player, value);
                return value;
            });
        }

        /** Read-modify-write on the stored value, online or not; an online player's cache follows. */
        public @NotNull CompletableFuture<T> update(@NotNull UUID player, @NotNull UnaryOperator<T> fn) {
            return vault.get().update(player, key, stored -> encode.apply(fn.apply(decode.apply(stored))))
                    .thenApply(stored -> {
                        T value = decode.apply(stored);
                        if (MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(player) != null) cache.put(player, value);
                        return value;
                    });
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerPrefs.class);
    private static final long LOAD_TIMEOUT_SECONDS = 10;

    private static final List<Entry<?, ?>> entries = new CopyOnWriteArrayList<>();
    private static volatile boolean installed;
    private static volatile Supplier<Vault> vault = () -> {
        throw new IllegalStateException("no vault yet: PlayerPrefs.vault(...) at boot");
    };

    private PlayerPrefs() {}

    public static <S> Entry<S, S> register(@NotNull VaultKey<S> key) {
        return register(key, Function.identity(), Function.identity());
    }

    public static <S, T> Entry<S, T> register(@NotNull VaultKey<S> key,
                                              @NotNull Function<S, T> decode, @NotNull Function<T, S> encode) {
        Entry<S, T> entry = new Entry<>(key, decode, encode);
        entries.add(entry);
        if (installed) catchUp(entry);
        return entry;
    }

    /** Where every preference is read and written. */
    public static void vault(@NotNull Supplier<Vault> source) {
        vault = source;
    }

    public static @NotNull Vault vault() {
        return vault.get();
    }

    /** Wire the login fetch + disconnect eviction; once, at boot. */
    public static void install() {
        if (installed) return;
        installed = true;
        var node = MinecraftServer.getGlobalEventHandler();
        node.addListener(AsyncPlayerConfigurationEvent.class, e -> {
            UUID player = e.getPlayer().getUuid();
            List<CompletableFuture<?>> loading = new ArrayList<>();
            for (Entry<?, ?> entry : entries) loading.add(fetch(entry, e.getPlayer()));
            try {
                CompletableFuture.allOf(loading.toArray(CompletableFuture[]::new))
                        .get(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception failed) {
                LOGGER.error("player data did not load for {}", e.getPlayer().getUsername(), failed);
                for (Entry<?, ?> entry : entries) entry.cache.remove(player);
                e.getPlayer().kick(Component.text("Couldn't load your data. Try again in a moment."));
            }
        });
        node.addListener(PlayerDisconnectEvent.class, e -> {
            for (Entry<?, ?> entry : entries) entry.cache.remove(e.getPlayer().getUuid());
        });
    }

    // a key whose class loads after a login would read null for that player, and its owner falls back to defaults
    private static void catchUp(Entry<?, ?> entry) {
        var connections = MinecraftServer.getConnectionManager();
        List<Player> connected = new ArrayList<>(connections.getOnlinePlayers());
        connected.addAll(connections.getConfigPlayers());
        for (Player online : connected) {
            try {
                fetch(entry, online).get(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception failed) {
                LOGGER.error("{} did not load for {}", entry.key.id(), online.getUsername(), failed);
            }
        }
    }

    private static <S, T> CompletableFuture<?> fetch(Entry<S, T> entry, Player player) {
        return vault.get().get(player.getUuid(), entry.key)
                .thenAccept(stored -> {
                    // a read landing after the timeout kick would otherwise refill the cache the kick cleared. The
                    // connection asks, not the online list: that holds only players past configuration, and the
                    // login fetch runs during it
                    if (!player.isOnline()) return;
                    // a set while the read was out is newer than what it read
                    entry.cache.putIfAbsent(player.getUuid(), entry.decode.apply(stored));
                });
    }
}
