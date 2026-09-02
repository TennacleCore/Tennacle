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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The 1v1 front: {@code /duel <player> [mode] [platform]} requests, dueling back accepts (the REQUESTER's mode
 * and platform win), and {@link Matches#start} does the rest. Modes register at boot (sumo, boxing, bridge).
 */
public final class Duels {

    private record Request(UUID target, String mode, @Nullable String platform, long at) {
        boolean expired() {
            return System.currentTimeMillis() - at > 60_000;
        }
    }

    private static final Map<UUID, Request> requests = new ConcurrentHashMap<>();
    private static String defaultMode;

    private Duels() {}

    /** Registers a mode with the match front; the first one registered is the {@code /duel <player>} default. */
    public static void mode(@NotNull Mode mode) {
        Matches.register(mode);
        if (defaultMode == null) defaultMode = mode.name();
    }

    public static void install() {
        MinecraftServer.getGlobalEventHandler().addListener(PlayerDisconnectEvent.class, e -> {
            UUID gone = e.getPlayer().getUuid();
            requests.remove(gone);
            requests.values().removeIf(request -> request.target().equals(gone));
        });
        Command duel = new Command("duel");
        var name = ArgumentType.Word("player");
        var mode = ArgumentType.Word("mode");
        var platform = ArgumentType.Word("platform");
        duel.setDefaultExecutor((sender, ctx) ->
                Msg.tag(sender, "duel", "usage: ", Msg.run("/duel <player> [" + String.join("|", Matches.modes())
                        + "] [" + String.join("|", Matches.platforms()) + "]")));
        duel.addSyntax((sender, ctx) -> request(sender, ctx.get(name), defaultMode, null), name);
        duel.addSyntax((sender, ctx) -> request(sender, ctx.get(name), ctx.get(mode), null), name, mode);
        duel.addSyntax((sender, ctx) -> request(sender, ctx.get(name), ctx.get(mode), ctx.get(platform)),
                name, mode, platform);
        MinecraftServer.getCommandManager().register(duel);
    }

    private static void request(Object sender, String targetName, String modeName, @Nullable String platformName) {
        if (!(sender instanceof Player me)) return;
        Mode mode = modeName != null ? Matches.mode(modeName) : null;
        if (mode == null) {
            Msg.err(me, "duel", "modes: ", Msg.v(String.join(", ", Matches.modes())));
            return;
        }
        if (platformName != null && Matches.platform(platformName) == null) {
            Msg.err(me, "duel", "platforms: ", Msg.v(String.join(", ", Matches.platforms())));
            return;
        }
        Player target = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(targetName);
        if (target == null) {
            Msg.err(me, "duel", "no player named ", Msg.v(targetName));
            return;
        }
        if (target == me) {
            Msg.err(me, "duel", "you can't duel yourself");
            return;
        }
        if (Game.of(me) != null) {
            Msg.err(me, "duel", "you're already in a duel");
            return;
        }
        if (Game.of(target) != null) {
            Msg.err(me, "duel", Msg.v(target.getUsername()), " is already in a duel");
            return;
        }
        Request theirs = requests.get(target.getUuid());
        if (theirs != null && theirs.expired()) {
            requests.remove(target.getUuid());
            theirs = null;
        }
        if (theirs != null && theirs.target().equals(me.getUuid())) { // accepting: the requester's mode wins
            requests.remove(target.getUuid());
            requests.remove(me.getUuid());
            start(Matches.mode(theirs.mode()), theirs.platform(), target, me);
            return;
        }
        requests.put(me.getUuid(), new Request(target.getUuid(), modeName, platformName, System.currentTimeMillis()));
        Msg.tag(me, "duel", modeName + " request sent to ", Msg.v(target.getUsername()));
        Msg.tag(target, "duel", Msg.v(me.getUsername()), " wants to " + modeName + " - ",
                Msg.run("/duel " + me.getUsername()), " to accept");
    }

    private static void start(Mode mode, @Nullable String platformName, Player a, Player b) {
        Platform platform = Matches.platformFor(mode, platformName);
        try {
            Matches.start(mode, platform, List.of(a, b));
        } catch (IllegalStateException e) { // the map may not be authored yet
            for (Player p : new Player[]{a, b}) Msg.err(p, "duel", e.getMessage());
            return;
        } catch (RuntimeException e) {
            // a bad ruleset path fails inside the launch, which tears its own shard down - nothing is orphaned
            for (Player p : new Player[]{a, b}) Msg.err(p, "duel", "could not start: " + e.getMessage());
            return;
        }
        // name the active platform: the REQUESTER's mode and platform win an accept, which is invisible otherwise
        String tag = mode.name() + (platformName != null ? " (" + platformName + ")" : "");
        Msg.tag(a, "duel", tag + " vs ", Msg.v(b.getUsername()));
        Msg.tag(b, "duel", tag + " vs ", Msg.v(a.getUsername()));
    }
}
