package tennacle.games;

import tennacle.Msg;

import io.github.term4.grotto.Game;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /queue <mode> [platform]}: the first two sides of the same size waiting for the same mode and platform
 * start a match. A party queues through its leader and counts as one side; {@code /queue leave} withdraws.
 */
public final class Queues {

    private record Entry(UUID leader, String mode, String platform, int size) {
        String bucket() {
            return mode + "|" + platform + "|" + size;
        }
    }

    private static final Map<UUID, Entry> waiting = new LinkedHashMap<>(); // insertion order = fairness

    private Queues() {}

    public static void install() {
        MinecraftServer.getGlobalEventHandler().addListener(PlayerDisconnectEvent.class, e -> {
            synchronized (waiting) {
                waiting.remove(e.getPlayer().getUuid());
            }
        });
        Command queue = new Command("queue");
        var mode = ArgumentType.Word("mode");
        var platform = ArgumentType.Word("platform");
        queue.setDefaultExecutor((sender, ctx) ->
                Msg.tag(sender, "queue", "usage: ", Msg.run("/queue <" + String.join("|", Matches.modes())
                        + "> [" + String.join("|", Matches.platforms()) + "] | /queue leave")));
        queue.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            if (ctx.get(mode).equals("leave")) {
                leave(p);
                return;
            }
            enqueue(p, ctx.get(mode), null);
        }, mode);
        queue.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) enqueue(p, ctx.get(mode), ctx.get(platform));
        }, mode, platform);
        MinecraftServer.getCommandManager().register(queue);
    }

    /** {@code true} when {@code player} was waiting. */
    /** Drops {@code player} from every wait: an entry they lead, and any party's they follow. A seat anywhere
     *  (a queue match, an accepted duel, a rejoin) calls this, so nobody can be paired twice. */
    public static void dequeue(@NotNull Player player) {
        synchronized (waiting) {
            waiting.remove(player.getUuid());
            waiting.values().removeIf(e -> {
                Player leader = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(e.leader());
                return leader == null || Parties.of(leader).contains(player);
            });
        }
    }

    public static boolean leave(@NotNull Player player) {
        Entry gone;
        synchronized (waiting) {
            gone = waiting.remove(player.getUuid());
        }
        if (gone == null) return false;
        Msg.tag(player, "queue", "left the " + gone.mode() + " queue");
        return true;
    }

    public static boolean waiting(@NotNull Player player) {
        synchronized (waiting) {
            return waiting.containsKey(player.getUuid());
        }
    }

    private static void enqueue(Player me, String modeName, @Nullable String platformName) {
        Mode mode = Matches.mode(modeName);
        if (mode == null) {
            Msg.err(me, "queue", "modes: ", Msg.v(String.join(", ", Matches.modes())));
            return;
        }
        if (platformName != null && Matches.platform(platformName) == null) {
            Msg.err(me, "queue", "platforms: ", Msg.v(String.join(", ", Matches.platforms())));
            return;
        }
        if (!Parties.leads(me)) {
            Msg.err(me, "queue", "your party leader queues for you");
            return;
        }
        List<Player> side = Parties.of(me);
        for (Player member : side) {
            if (Game.of(member) != null) {
                Msg.err(me, "queue", Msg.v(member.getUsername()), " is in a game");
                return;
            }
        }
        Platform platform = Matches.platformFor(mode, platformName);
        int seats;
        try {
            seats = Matches.seatsPerSide(mode);
        } catch (IllegalStateException e) { // the map may not be authored yet
            Msg.err(me, "queue", e.getMessage());
            return;
        }
        if (side.size() > seats) {
            Msg.err(me, "queue", mode.name() + " seats " + seats + " a side; your party is " + side.size());
            return;
        }
        Entry mine = new Entry(me.getUuid(), mode.name(), platform.name(), side.size());
        Entry match = null;
        synchronized (waiting) {
            waiting.remove(me.getUuid());
            for (Entry other : waiting.values()) {
                if (other.bucket().equals(mine.bucket())) {
                    match = other;
                    break;
                }
            }
            if (match != null) waiting.remove(match.leader());
            else waiting.put(me.getUuid(), mine);
        }
        if (match == null) {
            Msg.tag(me, "queue", "queued for " + mode.name() + " (" + platform.name() + ") - ", Msg.run("/queue leave"));
            return;
        }
        Player them = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(match.leader());
        if (them == null) { // left between the two commands
            enqueue(me, modeName, platformName);
            return;
        }
        for (Player p : Parties.of(them)) {
            if (Game.of(p) != null) { // their entry went stale: seated since they queued (only I was re-checked)
                enqueue(me, modeName, platformName);
                return;
            }
        }
        start(mode, platform, Parties.of(them), side);
    }

    private static void start(Mode mode, Platform platform, List<Player> a, List<Player> b) {
        try {
            Matches.start(mode, platform, List.of(a, b));
        } catch (RuntimeException e) {
            for (Player p : a) Msg.err(p, "queue", "could not start: " + e.getMessage());
            for (Player p : b) Msg.err(p, "queue", "could not start: " + e.getMessage());
            return;
        }
        String tag = mode.name() + " (" + platform.name() + ")";
        for (Player p : a) Msg.tag(p, "queue", tag + " vs ", Msg.v(names(b)));
        for (Player p : b) Msg.tag(p, "queue", tag + " vs ", Msg.v(names(a)));
    }

    private static String names(List<Player> side) {
        return String.join(", ", side.stream().map(Player::getUsername).toList());
    }
}
