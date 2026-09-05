package tennacle.games;

import tennacle.Msg;

import io.github.term4.polyp.platform.player.PlayerNames;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The duel front: {@code /duel <player> <mode> [map] [-flag ...]} requests; {@code /duel accept <player>}, or
 * dueling back, accepts with the REQUESTER's mode and {@link Options}; {@link Matches#start} seats each party as a
 * side. {@code /duel <player>} alone has no menu to open yet and says so. Modes register at boot.
 */
public final class Duels {

    private record Request(UUID target, String mode, Options options, long at) {
        boolean expired() {
            return System.currentTimeMillis() - at > 60_000;
        }
    }

    private static final Map<UUID, Request> requests = new ConcurrentHashMap<>();

    private Duels() {}

    public static void mode(@NotNull Mode mode) {
        Matches.register(mode);
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
        duel.setDefaultExecutor((sender, ctx) -> usage(sender));
        duel.addSyntax((sender, ctx) -> answer(sender, ctx.get(name)), name);
        // "/duel accept <player>" reads as a player named accept and their mode: the word is the accept
        duel.addSyntax((sender, ctx) -> request(sender, ctx.get(name), ctx.get(mode), List.of()), name, mode);
        duel.addSyntax((sender, ctx) -> request(sender, ctx.get(name), ctx.get(mode), List.of(ctx.get(options))),
                name, mode, options);
        MinecraftServer.getCommandManager().register(duel);

        Command flags = new Command("flags");
        var which = ArgumentType.Word("mode");
        which.setSuggestionCallback(Completions.of(Matches::modes));
        flags.setDefaultExecutor((sender, ctx) -> Matches.modes().forEach(m -> listFlags(sender, m)));
        flags.addSyntax((sender, ctx) -> listFlags(sender, ctx.get(which)), which);
        MinecraftServer.getCommandManager().register(flags);
    }

    private static void usage(CommandSender to) {
        Msg.tag(to, "duel", "usage: ", Msg.run("/duel <player> <" + String.join("|", Matches.modes())
                + "> [map] [-preset=<" + String.join("|", Matches.platforms()) + ">]"));
    }

    // /duel <player>: dueling back accepts; anything else has no menu to open yet
    private static void answer(CommandSender sender, String targetName) {
        if (!(sender instanceof Player me)) return;
        if (targetName.equalsIgnoreCase("accept")) {
            usage(me);
            return;
        }
        Player target = online(me, targetName);
        if (target == null || !leads(me)) return;
        Request theirs = pending(target, me);
        if (theirs != null) {
            launch(theirs, target, me);
            return;
        }
        Msg.err(me, "duel", "the duel menu is not made yet - name a mode: ",
                Msg.run("/duel " + target.getUsername() + " <" + String.join("|", Matches.modes()) + ">"));
    }

    private static void accept(Player me, String fromName) {
        Player from = online(me, fromName);
        if (from == null || !leads(me)) return;
        Request theirs = pending(from, me);
        if (theirs == null) {
            Msg.err(me, "duel", "no duel request from ", Msg.v(from.getUsername()));
            return;
        }
        launch(theirs, from, me);
    }

    private static void request(CommandSender sender, String targetName, String modeName, List<String> words) {
        if (!(sender instanceof Player me)) return;
        if (targetName.equalsIgnoreCase("accept")) {
            accept(me, modeName);
            return;
        }
        Mode mode = Matches.mode(modeName);
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
        Player target = online(me, targetName);
        if (target == null || !leads(me)) return;
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
        Request theirs = pending(target, me);
        if (theirs != null) { // dueling back accepts: the requester's mode and options win
            launch(theirs, target, me);
            return;
        }
        requests.put(me.getUuid(), new Request(target.getUuid(), mode.name(), options, System.currentTimeMillis()));
        // scrims' lines on the house palette: the sentence in the accent, the name in prose, the map or pool a value
        String what = title(mode) + " duel request";
        me.sendMessage(Msg.THEME.accent("Sent a " + what + " to ").append(Msg.THEME.prose(target.getUsername())).append(on(mode, options)));
        flagsLine(me, options);
        target.sendMessage(Msg.THEME.accent(what + " from ").append(Msg.THEME.prose(me.getUsername())).append(on(mode, options)));
        target.sendMessage(acceptLine(me));
        flagsLine(target, options);
    }

    private static @Nullable Player online(Player me, String name) {
        Player target = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name);
        if (target == null) {
            Msg.err(me, "duel", "no player named ", Msg.v(name));
            return null;
        }
        if (target == me) {
            Msg.err(me, "duel", "you can't duel yourself");
            return null;
        }
        return target;
    }

    private static boolean leads(Player me) {
        if (Parties.leads(me)) return true;
        Msg.err(me, "duel", "your party leader duels for you");
        return false;
    }

    /** {@code from}'s live request aimed at {@code to}, or null. */
    private static @Nullable Request pending(Player from, Player to) {
        Request theirs = requests.get(from.getUuid());
        if (theirs == null) return null;
        if (theirs.expired()) {
            requests.remove(from.getUuid());
            return null;
        }
        return theirs.target().equals(to.getUuid()) ? theirs : null;
    }

    private static void launch(Request theirs, Player requester, Player accepter) {
        requests.remove(requester.getUuid());
        requests.remove(accepter.getUuid());
        start(Matches.mode(theirs.mode()), theirs.options(), requester, accepter);
    }

    private static String title(Mode mode) {
        return Character.toUpperCase(mode.name().charAt(0)) + mode.name().substring(1);
    }

    // the map when one was named, else the pool it will come from
    private static Component on(Mode mode, Options options) {
        return Msg.THEME.accent(" on ").append(Msg.v(options.map() != null ? options.map() : mode.arena().label()));
    }

    // scrims' [Accept]: a bold green click that runs the accept, the hover naming what it runs
    private static Component acceptLine(Player requester) {
        String command = "/duel accept " + requester.getUsername();
        return Msg.THEME.good("[Accept]").decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Msg.THEME.prose("Click to accept!").append(Component.newline())
                        .append(Msg.THEME.dim("Runs ")).append(Msg.THEME.accent(command)).append(Msg.THEME.dim(" on click."))));
    }

    // "Duel Flags: -preset=minemen" with scrims' info glyph; the hover says what each flag is, one line each
    private static void flagsLine(Player to, Options options) {
        List<String> flags = options.flags();
        if (flags.isEmpty()) return;
        Component hover = Component.empty(); // siblings of the bold glyph, not its children: the text stays plain
        for (int i = 0; i < flags.size(); i++) {
            String word = flags.get(i);
            int eq = word.indexOf('=');
            Flag flag = Flags.get(word.substring(1, eq > 0 ? eq : word.length()));
            if (i > 0) hover = hover.append(Component.newline());
            hover = hover.append(Msg.v(word)).append(Msg.THEME.prose(": " + (flag != null ? flag.description() : "")));
        }
        to.sendMessage(Msg.THEME.prose("Duel Flags: ").append(Msg.v(String.join(" ", flags)))
                .append(Component.text(" ⓘ", NamedTextColor.BLUE, TextDecoration.BOLD).hoverEvent(HoverEvent.showText(hover))));
    }

    private static void listFlags(CommandSender to, String modeName) {
        Mode mode = Matches.mode(modeName);
        if (mode == null) {
            Msg.err(to, "flags", "modes: ", Msg.v(String.join(", ", Matches.modes())));
            return;
        }
        List<Flag> menu = Flags.of(mode);
        if (menu.isEmpty()) {
            Msg.tag(to, "flags", mode.name() + " offers no flags");
            return;
        }
        Msg.tag(to, "flags", mode.name() + ":");
        for (Flag flag : menu) {
            Collection<String> values = flag.values(mode);
            String form = "-" + flag.name() + (values.isEmpty() ? "" : "=<" + String.join("|", values) + ">");
            Msg.tag(to, "flags", "  ", Msg.v(form), "  " + flag.description());
        }
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
