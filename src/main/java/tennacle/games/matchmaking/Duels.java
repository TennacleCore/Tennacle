package tennacle.games.matchmaking;

import tennacle.looks.GameSounds;
import tennacle.commands.Completions;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.Platform;
import tennacle.games.Options;
import tennacle.games.Flag;
import tennacle.games.Flags;
import tennacle.Msg;
import tennacle.commands.AppCommand;
import tennacle.commands.Commands;

import io.github.term4.canvas.theme.SoundSet;
import io.github.term4.polyp.platform.player.PlayerNames;
import io.github.term4.grotto.Game;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
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
import java.util.concurrent.CompletableFuture;

/**
 * The duel front: {@code /duel <player> <mode> [map] [-flag ...]} requests; {@code /duel accept <player>} - the
 * [Accept] click - is the one way to accept, with the REQUESTER's mode and {@link Options}. A request is party
 * to party: only a leader sends one out, everyone in the party it reaches reads it and any of them may accept
 * while the party stands, and the accept seats both parties as the sides. Broken up - a member kicked, the party
 * disbanded - the one who led it when it arrived may still accept, alone; nobody else may. Two players of one
 * party duel each other 1v1 without leaving it. Dueling back is another request, never an accept.
 * {@code /duel <player>} alone has no menu yet. Modes register at boot.
 */
public final class Duels {

    /** {@code within}: a 1v1 between party mates, which only the one named accepts. {@code targetParty} is the
     *  party it reached ({@link Parties#partyOf}), {@code targetLeader} who led it then. */
    private record Request(UUID from, UUID target, UUID targetParty, UUID targetLeader, boolean within, String mode,
                           Options options, long at) {
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
            requests.values().removeIf(request -> request.targetLeader().equals(gone) || request.within() && request.target().equals(gone));
        });
        AppCommand duel = Commands.player("duel", "Challenge a player, and their party, to a match.")
                .usage("/duel <player> <mode> [map] [-preset=<name>]", "send a request")
                .usage("/duel accept <player>", "accept theirs")
                .featured();
        var name = ArgumentType.Word("player");
        name.setSuggestionCallback(PlayerNames.suggest());
        var mode = ArgumentType.Word("mode");
        mode.setSuggestionCallback(Completions.of(Matches::launchedFull)); // modes register after this command does
        var options = ArgumentType.StringArray("options");
        options.setSuggestionCallback((sender, ctx, suggestion) -> {
            Mode picked = Matches.mode(ctx.get(mode));
            if (picked != null) Completions.offer(suggestion, Options.completions(picked, Completions.partial(suggestion)));
        });
        duel.addSyntax((sender, ctx) -> answer(sender, ctx.get(name)), name);
        // "/duel accept <player>" reads as a player named accept and their mode: the word is the accept
        duel.addSyntax((sender, ctx) -> request(sender, ctx.get(name), ctx.get(mode), List.of()), name, mode);
        duel.addSyntax((sender, ctx) -> request(sender, ctx.get(name), ctx.get(mode), List.of(ctx.get(options))),
                name, mode, options);
        Commands.register(duel);

        AppCommand flags = Commands.player("flags", "The flags a mode's duels take.")
                .usage("/flags [mode]", "one mode's, or every mode's");
        var which = ArgumentType.Word("mode");
        which.setSuggestionCallback(Completions.of(Matches::launchedFull));
        flags.setDefaultExecutor((sender, ctx) -> Matches.launchedFull().forEach(m -> listFlags(sender, m)));
        flags.addSyntax((sender, ctx) -> listFlags(sender, ctx.get(which)), which);
        Commands.register(flags);
    }

    // /duel <player>: no menu to open yet, and a pending request from them changes nothing - only the accept accepts
    private static void answer(CommandSender sender, String targetName) {
        if (!(sender instanceof Player me)) return;
        if (targetName.equalsIgnoreCase("accept")) {
            Commands.usage(me, "duel");
            return;
        }
        Player target = online(me, targetName);
        if (target == null) return;
        tennacle.lobby.menus.PlayerMenu.duel(me, target);
    }

    /** {@code /duel <target> <mode> [-preset=<preset>]} from {@code me}. */
    public static void request(@NotNull Player me, @NotNull Player target, @NotNull Mode mode, @Nullable String preset) {
        request(me, target.getUsername(), mode.name(), preset != null ? List.of("-preset=" + preset) : List.of());
    }

    private static void accept(Player me, String fromName) {
        Player from = online(me, fromName);
        if (from == null) return;
        Request theirs = pending(from, me);
        if (theirs == null) {
            Msg.fail(me, "No duel request from ", Msg.v(from.getUsername()), ".");
            return;
        }
        List<Player> a = theirs.within() ? List.of(from) : Parties.of(from);
        List<Player> b = theirs.within() ? List.of(me) : Parties.of(me);
        if (!free(me, a) || !free(me, b)) return;
        // the request outlives a launch that failed, so the click can be tried again
        start(Matches.mode(theirs.mode()), theirs.options(), a, b).thenRun(() -> {
            requests.remove(from.getUuid());
            requests.remove(me.getUuid());
        });
    }

    private static void request(CommandSender sender, String targetName, String modeName, List<String> words) {
        if (!(sender instanceof Player me)) return;
        if (targetName.equalsIgnoreCase("accept")) {
            accept(me, modeName);
            return;
        }
        Mode mode = Matches.mode(modeName);
        if (mode == null) {
            Msg.fail(me, "No mode named ", Msg.v(modeName), ". Modes: ", Msg.v(String.join(", ", Matches.launchedFull())), ".");
            return;
        }
        if (Matches.joinable(mode)) {
            Msg.fail(me, Msg.v(mode.title()), " fills up through ", Msg.run(Play.line(mode)), ".");
            return;
        }
        Options options;
        try {
            options = Options.parse(mode, words);
        } catch (IllegalArgumentException e) {
            Msg.fail(me, e.getMessage());
            return;
        }
        Player target = online(me, targetName);
        if (target == null) return;
        if (!tennacle.lobby.Settings.DUEL_REQUESTS.of(target).admits(me, target)) {
            Msg.fail(me, Msg.v(target.getUsername()), " takes no duel requests.");
            return;
        }
        boolean within = Parties.together(me, target); // party mates: a 1v1 between the two, whoever leads
        if (!within && !Parties.requireLeader(me)) return;
        List<Player> mine = within ? List.of(me) : Parties.of(me);
        List<Player> theirs = within ? List.of(target) : Parties.of(target);
        if (!free(me, mine) || !free(me, theirs)) return;
        requests.put(me.getUuid(), new Request(me.getUuid(), target.getUuid(), Parties.partyOf(target),
                Parties.leaderOf(target), within, mode.name(), options, System.currentTimeMillis()));
        // scrims' lines as captured: a yellow sentence, the name in prose, the map or pool a value
        String what = mode.title() + " duel request";
        Component warning = mine.size() == theirs.size() ? null
                : Component.text("WARNING: Unbalanced team sizes (" + mine.size() + "v" + theirs.size() + ")", NamedTextColor.RED);
        Component sent = Component.text("Sent a " + what + " to ", NamedTextColor.YELLOW)
                .append(Msg.THEME.prose(target.getUsername())).append(on(mode, options));
        for (Player member : mine) {
            member.sendMessage(sent);
            flagsLine(member, options);
            if (warning != null) member.sendMessage(warning);
        }
        Component received = Component.text(what + " from ", NamedTextColor.YELLOW)
                .append(Msg.THEME.prose(me.getUsername())).append(on(mode, options));
        for (Player member : theirs) {
            member.sendMessage(received);
            member.sendMessage(acceptLine(me));
            flagsLine(member, options);
            if (warning != null) member.sendMessage(warning);
            GameSounds.lobby(member, SoundSet.DUEL_REQUEST);
        }
    }

    private static @Nullable Player online(Player me, String name) {
        Player target = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name);
        if (target == null) {
            Msg.fail(me, "No player named ", Msg.v(name), ".");
            return null;
        }
        if (target == me) {
            Msg.fail(me, "You can't duel yourself.");
            return null;
        }
        return target;
    }

    /** Whether everyone on {@code side} is out of a game; {@code me} hears who is not. */
    private static boolean free(Player me, Collection<Player> side) {
        for (Player member : side) {
            if (!Matches.grotto().engaged(member)) continue;
            if (member == me) Msg.fail(me, "You're already in a game.");
            else Msg.fail(me, Msg.v(member.getUsername()), " is already in a game.");
            return false;
        }
        return true;
    }

    /** {@code from}'s live request that {@code to} may accept: the one named for a 1v1; anyone in the party it
     *  reached while that party stands, else the one who led it, if they still speak for themselves. */
    private static @Nullable Request pending(Player from, Player to) {
        Request theirs = requests.get(from.getUuid());
        if (theirs == null) return null;
        if (theirs.expired()) {
            requests.remove(from.getUuid());
            return null;
        }
        boolean mine = theirs.within() ? theirs.target().equals(to.getUuid())
                : Parties.partyOf(to).equals(theirs.targetParty())
                || theirs.targetLeader().equals(to.getUuid()) && Parties.leads(to);
        return mine ? theirs : null;
    }

    // the map when one was named, else the pool it will come from
    private static Component on(Mode mode, Options options) {
        return Component.text(" on ", NamedTextColor.YELLOW).append(Msg.v(options.map() != null ? options.map() : mode.arena().label()));
    }

    // scrims' [Accept]: a bold green click that runs the accept
    private static Component acceptLine(Player requester) {
        return Msg.THEME.good("[Accept]").decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/duel accept " + requester.getUsername()))
                .hoverEvent(HoverEvent.showText(Msg.THEME.prose("Click to accept!")));
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
            Msg.fail(to, "No mode named ", Msg.v(modeName), ". Modes: ", Msg.v(String.join(", ", Matches.modes())), ".");
            return;
        }
        List<Flag> menu = Flags.of(mode);
        if (menu.isEmpty()) {
            Msg.say(to, mode.title() + " takes no flags.");
            return;
        }
        Msg.say(to, mode.title() + " flags:");
        for (Flag flag : menu) {
            Collection<String> values = flag.values(mode);
            String form = "-" + flag.name() + (values.isEmpty() ? "" : "=<" + String.join("|", values) + ">");
            Msg.say(to, "  ", Msg.v(form), "  " + flag.description());
        }
    }

    /** The match, once its map is up; both sides hear why when it fails. */
    private static CompletableFuture<Game> start(Mode mode, Options options, List<Player> a, List<Player> b) {
        Platform platform = Matches.platformFor(options.platform());
        CompletableFuture<Game> launch = Matches.begin(mode, platform, List.of(a, b), options);
        launch.whenComplete((game, failure) -> {
            if (failure != null) {
                // a missing map says so; a bad ruleset path fails inside the launch, which tears its own shard down
                String why = failure instanceof IllegalStateException ? failure.getMessage()
                        : "The duel could not start: " + failure.getMessage();
                for (Player p : a) Msg.fail(p, why);
                for (Player p : b) Msg.fail(p, why);
                return;
            }
            // name what runs: the REQUESTER's mode and options win an accept, which is invisible otherwise
            String what = mode.title() + options.describe();
            for (Player p : a) Msg.say(p, what + " vs ", Msg.v(names(b)), ".");
            for (Player p : b) Msg.say(p, what + " vs ", Msg.v(names(a)), ".");
        });
        return launch;
    }

    private static String names(List<Player> side) {
        return String.join(", ", side.stream().map(Player::getUsername).toList());
    }
}
