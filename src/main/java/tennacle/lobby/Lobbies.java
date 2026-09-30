package tennacle.lobby;

import io.github.term4.archipelago.shard.ShardTabList;
import io.github.term4.grotto.event.SeatChangeEvent;
import io.github.term4.polyp.platform.player.Wardrobe;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.games.Matches;
import tennacle.games.Mode;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * The lobby worlds: the main lobby a login lands in, then one per kind of game, each hosting the modes its NPCs
 * start. A player's home is where a game sends them back: the lobby hosting the game they last sat in, else the
 * lobby they were last in.
 */
public final class Lobbies {

    public record Lobby(@NotNull String id, @NotNull String name, @NotNull Material icon, @NotNull String blurb,
                        @NotNull Predicate<Mode> hosting, @NotNull InstanceContainer world, @NotNull Pos spawn) {

        public boolean hosts(@NotNull Mode mode) {
            return hosting.test(mode);
        }

        /** The registered modes it hosts, in registration order. */
        public @NotNull List<Mode> modes() {
            return Matches.modes().stream().map(Matches::mode).filter(Objects::nonNull).filter(this::hosts).toList();
        }
    }

    private static final List<Lobby> LOBBIES = new CopyOnWriteArrayList<>();
    private static final Map<UUID, Lobby> HOME = new ConcurrentHashMap<>();

    private Lobbies() {}

    /** The main lobby on {@code main}, the login world, then a Bed Wars and a Duels lobby of their own. */
    public static void install(@NotNull InstanceContainer main) {
        add("main", "Main Lobby", Material.BEACON, "Every game in one place.", mode -> true, main, Playground.SPAWN);
        add("bedwars", "Bed Wars", Material.RED_BED, "Solo, Doubles, 3v3v3v3 and 4v4v4v4.",
                mode -> mode.name().startsWith("bedwars"), Playground.createWorld(false), Playground.SPAWN);
        add("duels", "Duels", Material.IRON_SWORD, "Bridge, Sumo, Boxing and Bed Fight.",
                mode -> mode.family() == Mode.Family.DUEL, Playground.createWorld(false), Playground.SPAWN);
        var events = MinecraftServer.getGlobalEventHandler();
        events.addListener(SeatChangeEvent.class, e -> {
            Mode mode = e.taken() ? Matches.modeOf(e.game()) : null;
            if (mode != null) HOME.put(e.player().getUuid(), hosting(mode));
        });
        events.addListener(PlayerSpawnEvent.class, e -> {
            Lobby lobby = of(e.getSpawnInstance());
            if (lobby != null) HOME.put(e.getPlayer().getUuid(), lobby);
        });
        events.addListener(PlayerDisconnectEvent.class, e -> HOME.remove(e.getPlayer().getUuid()));
    }

    /** A lobby over {@code world}, dressed like every other; the first added is the main one. */
    public static @NotNull Lobby add(@NotNull String id, @NotNull String name, @NotNull Material icon, @NotNull String blurb,
                                     @NotNull Predicate<Mode> hosting, @NotNull InstanceContainer world, @NotNull Pos spawn) {
        Lobby lobby = new Lobby(id, name, icon, blurb, hosting, world, spawn);
        if (!LOBBIES.isEmpty()) Wardrobe.outfit(world, Playground::outfit);
        LOBBIES.add(lobby);
        return lobby;
    }

    public static @NotNull Lobby main() {
        return LOBBIES.getFirst();
    }

    public static @NotNull List<Lobby> all() {
        return List.copyOf(LOBBIES);
    }

    public static @Nullable Lobby of(@Nullable Instance world) {
        for (Lobby lobby : LOBBIES) {
            if (lobby.world() == world) return lobby;
        }
        return null;
    }

    public static @Nullable Lobby in(@NotNull Player player) {
        return of(player.getInstance());
    }

    /** The lobby whose NPCs start {@code mode}: the first besides the main one, else the main one. */
    public static @NotNull Lobby hosting(@NotNull Mode mode) {
        for (Lobby lobby : LOBBIES.subList(1, LOBBIES.size())) {
            if (lobby.hosts(mode)) return lobby;
        }
        return main();
    }

    public static @NotNull Lobby home(@NotNull Player player) {
        return HOME.getOrDefault(player.getUuid(), main());
    }

    /** Puts {@code player} at {@code lobby}'s spawn, dressed for it. */
    public static void send(@NotNull Player player, @NotNull Lobby lobby) {
        if (player.getInstance() == lobby.world()) { // Minestom rejects a same-instance setInstance: no spawn event either
            player.teleport(lobby.spawn(), Vec.ZERO);
            Wardrobe.arrive(player, lobby.world());
            ShardTabList.resync(player); // the spawn's own re-list, which a same-instance return never gets
        } else {
            player.setVelocity(Vec.ZERO); // setInstance would carry the fall they left with
            player.setInstance(lobby.world(), lobby.spawn());
        }
    }
}
