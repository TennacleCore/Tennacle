package tennacle.net;

import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.timer.TaskSchedule;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tennacle.moderation.Vanish;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** This process's bus, presence and name among the servers. Set before anything subscribes. */
public final class Network {

    private static final Logger LOGGER = LoggerFactory.getLogger(Network.class);

    private static volatile Bus bus = new LocalBus();
    private static volatile Presence presence = new LocalPresence();
    private static volatile String server = "server-1";
    private static final Map<UUID, Long> joinedAt = new ConcurrentHashMap<>();
    private static volatile boolean down;

    private Network() {}

    public static void use(@NotNull Bus events, @NotNull Presence online, @NotNull String name) {
        bus = events;
        presence = online;
        server = name;
    }

    public static @NotNull Bus bus() {
        return bus;
    }

    public static @NotNull Presence presence() {
        return presence;
    }

    /** This process's name: {@code tennacle.server-id}. */
    public static @NotNull String server() {
        return server;
    }

    /** A game server's players into presence: on join, on leave, and a beat every 30 seconds. */
    public static void install() {
        var events = MinecraftServer.getGlobalEventHandler();
        events.addListener(PlayerSpawnEvent.class, e -> {
            if (!e.isFirstSpawn()) return;
            joinedAt.put(e.getPlayer().getUuid(), System.currentTimeMillis());
            seen(e.getPlayer());
        });
        events.addListener(PlayerDisconnectEvent.class, e -> {
            joinedAt.remove(e.getPlayer().getUuid());
            settle("leave", presence.left(e.getPlayer().getUuid(), server));
        });
        MinecraftServer.getSchedulerManager().buildTask(() -> {
            List<Presence.Here> online = MinecraftServer.getConnectionManager().getOnlinePlayers().stream()
                    .filter(p -> joinedAt.containsKey(p.getUuid())).map(Network::here).toList();
            settle("beat", presence.beat(server, online));
        }).repeat(TaskSchedule.seconds(30)).schedule();
    }

    /** Their entry again: a vanish turned on or off. */
    public static void seen(@NotNull Player player) {
        if (!joinedAt.containsKey(player.getUuid())) return;
        settle("join", presence.joined(here(player)));
    }

    private static Presence.Here here(Player player) {
        return new Presence.Here(player.getUuid(), player.getUsername(), server,
                joinedAt.getOrDefault(player.getUuid(), System.currentTimeMillis()), Vanish.vanished(player));
    }

    // once per outage, not once a beat
    private static void settle(String what, CompletableFuture<Void> call) {
        call.whenComplete((ok, failed) -> {
            if (failed == null) {
                if (down) LOGGER.info("presence is back");
                down = false;
            } else {
                if (!down) LOGGER.warn("presence {} failed; quiet until it works again", what, failed);
                down = true;
            }
        });
    }
}
