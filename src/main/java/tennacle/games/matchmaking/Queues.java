package tennacle.games.matchmaking;

import tennacle.commands.Completions;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.Platform;
import tennacle.Msg;
import tennacle.commands.Commands;
import tennacle.commands.AppCommand;

import net.minestom.server.MinecraftServer;
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
        AppCommand queue = Commands.player("queue", "Wait for the next side that wants the same game.")
                .usage("/queue <mode> [platform]", "join the line")
                .usage("/queue leave", "step out of it")
                .featured();
        var mode = ArgumentType.Word("mode");
        mode.setSuggestionCallback(Completions.of(Matches::modes));
        var platform = ArgumentType.Word("platform");
        platform.setSuggestionCallback(Completions.of(Matches::platforms));
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
        Commands.register(queue);
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
        Msg.say(player, "Left the ", Msg.v(gone.mode()), " queue.");
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
            Msg.fail(me, "No mode named ", Msg.v(modeName), ". Modes: ", Msg.v(String.join(", ", Matches.modes())), ".");
            return;
        }
        if (platformName != null && Matches.platform(platformName) == null) {
            Msg.fail(me, "No platform named ", Msg.v(platformName), ". Platforms: ", Msg.v(String.join(", ", Matches.platforms())), ".");
            return;
        }
        if (!Parties.requireLeader(me)) return;
        List<Player> side = Parties.of(me);
        for (Player member : side) {
            if (Matches.grotto().engaged(member)) {
                Msg.fail(me, Msg.v(member.getUsername()), " is in a game.");
                return;
            }
        }
        Platform platform = Matches.platformFor(platformName);
        int seats;
        try {
            seats = Matches.seatsPerSide(mode);
        } catch (IllegalStateException e) { // the map may not be authored yet
            Msg.fail(me, e.getMessage());
            return;
        }
        if (side.size() > seats) {
            Msg.fail(me, mode.title() + " seats " + seats + " a side; your party is " + side.size() + ".");
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
            Msg.ok(me, "Queued for ", Msg.v(label(mode, platform)), ". ", Msg.run("/queue leave"), " to step out.");
            return;
        }
        Player them = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(match.leader());
        if (them == null) { // left between the two commands
            enqueue(me, modeName, platformName);
            return;
        }
        List<Player> theirs = Parties.of(them);
        for (Player p : theirs) {
            if (Matches.grotto().engaged(p)) { // their entry went stale: seated since they queued (only I was re-checked)
                enqueue(me, modeName, platformName);
                return;
            }
        }
        // their party may have shrunk since they queued - the party listener runs first and only their own
        // entry is dropped, so the bucket still claims the old size
        if (theirs.size() != match.size()) {
            enqueue(me, modeName, platformName);
            return;
        }
        start(mode, platform, theirs, side);
    }

    private static void start(Mode mode, Platform platform, List<Player> a, List<Player> b) {
        try {
            Matches.start(mode, platform, List.of(a, b));
        } catch (RuntimeException e) {
            for (Player p : a) Msg.fail(p, "The match could not start: " + e.getMessage());
            for (Player p : b) Msg.fail(p, "The match could not start: " + e.getMessage());
            return;
        }
        String what = label(mode, platform);
        for (Player p : a) Msg.say(p, what + " vs ", Msg.v(names(b)), ".");
        for (Player p : b) Msg.say(p, what + " vs ", Msg.v(names(a)), ".");
    }

    // the platform only when one was asked for
    private static String label(Mode mode, Platform platform) {
        return mode.title() + (platform.name().equals(Platform.DEFAULT.name()) ? "" : " (" + platform.name() + ")");
    }

    private static String names(List<Player> side) {
        return String.join(", ", side.stream().map(Player::getUsername).toList());
    }
}
