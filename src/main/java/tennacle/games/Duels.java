package tennacle.games;

import tennacle.Msg;

import io.github.term4.grotto.Game;
import io.github.term4.polyp.platform.player.PlayerNames;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The duel front: {@code /duel <player> [mode] [map] [-flag ...]} requests, dueling back accepts (the REQUESTER's
 * mode and {@link Options} win), and {@link Matches#start} seats each party as a side. Modes register at boot.
 */
public final class Duels {

    private record Request(UUID target, String mode, Options options, long at) {
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
        name.setSuggestionCallback(PlayerNames.suggest());
        var mode = ArgumentType.Word("mode");
        mode.setSuggestionCallback(Completions.of(Matches::modes)); // modes register after this command does
        var options = ArgumentType.StringArray("options");
        options.setSuggestionCallback((sender, ctx, suggestion) -> {
            Mode picked = Matches.mode(ctx.get(mode));
            if (picked != null) Completions.offer(suggestion, Options.completions(picked, Completions.partial(suggestion)));
        });
        duel.setDefaultExecutor((sender, ctx) ->
                Msg.tag(sender, "duel", "usage: ", Msg.run("/duel <player> [" + String.join("|", Matches.modes())
                        + "] [map] [-preset=<" + String.join("|", Matches.platforms()) + ">]")));
        duel.addSyntax((sender, ctx) -> request(sender, ctx.get(name), defaultMode, List.of()), name);
        duel.addSyntax((sender, ctx) -> request(sender, ctx.get(name), ctx.get(mode), List.of()), name, mode);
        duel.addSyntax((sender, ctx) -> request(sender, ctx.get(name), ctx.get(mode), List.of(ctx.get(options))),
                name, mode, options);
        MinecraftServer.getCommandManager().register(duel);
    }

    private static void request(Object sender, String targetName, String modeName, List<String> words) {
        if (!(sender instanceof Player me)) return;
        Mode mode = modeName != null ? Matches.mode(modeName) : null;
        if (mode == null) {
            Msg.err(me, "duel", "modes: ", Msg.v(String.join(", ", Matches.modes())));
            return;
        }
        Options options;
        try {
            options = Options.parse(mode, words);
        } catch (IllegalArgumentException e) {
            Msg.err(me, "duel", e.getMessage());
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
        if (!Parties.leads(me)) {
            Msg.err(me, "duel", "your party leader duels for you");
            return;
        }
        for (Player member : Parties.of(me)) {
            if (Matches.grotto().engaged(member)) {
                Msg.err(me, "duel", member == me ? "you're already in a duel" : member.getUsername() + " is already in a duel");
                return;
            }
        }
        for (Player member : Parties.of(target)) {
            if (Matches.grotto().engaged(member)) {
                Msg.err(me, "duel", Msg.v(member.getUsername()), " is already in a duel");
                return;
            }
        }
        Request theirs = requests.get(target.getUuid());
        if (theirs != null && theirs.expired()) {
            requests.remove(target.getUuid());
            theirs = null;
        }
        if (theirs != null && theirs.target().equals(me.getUuid())) { // accepting: the requester's mode and options win
            requests.remove(target.getUuid());
            requests.remove(me.getUuid());
            start(Matches.mode(theirs.mode()), theirs.options(), target, me);
            return;
        }
        requests.put(me.getUuid(), new Request(target.getUuid(), modeName, options, System.currentTimeMillis()));
        Msg.tag(me, "duel", modeName + options.describe() + " request sent to ", Msg.v(target.getUsername()));
        Msg.tag(target, "duel", Msg.v(me.getUsername()), " wants to " + modeName + options.describe() + " - ",
                Msg.run("/duel " + me.getUsername()), " to accept");
    }

    private static void start(Mode mode, Options options, Player a, Player b) {
        Platform platform = Matches.platformFor(options.platform());
        try {
            Matches.start(mode, platform, List.of(Parties.of(a), Parties.of(b)), options);
        } catch (IllegalStateException e) { // the map may not be authored yet
            for (Player p : new Player[]{a, b}) Msg.err(p, "duel", e.getMessage());
            return;
        } catch (RuntimeException e) {
            // a bad ruleset path fails inside the launch, which tears its own shard down - nothing is orphaned
            for (Player p : new Player[]{a, b}) Msg.err(p, "duel", "could not start: " + e.getMessage());
            return;
        }
        // name what runs: the REQUESTER's mode and options win an accept, which is invisible otherwise
        String tag = mode.name() + options.describe();
        Msg.tag(a, "duel", tag + " vs ", Msg.v(b.getUsername()));
        Msg.tag(b, "duel", tag + " vs ", Msg.v(a.getUsername()));
    }
}
