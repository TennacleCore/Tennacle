package tennacle.games;

import tennacle.games.watch.Spectators;
import tennacle.Msg;
import tennacle.Sessions;
import tennacle.games.matchmaking.Queues;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.roster.Slot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import tennacle.commands.AppCommand;
import tennacle.commands.Commands;
/** {@code /spawn} (aliases {@code /lobby}, {@code /l}): back to the lobby from anywhere, or to a game's with
 *  {@code /l bedwars}. A seated player
 *  departs their game - the seat follows the resolved reclaim rule, {@code /games} lists what holds one, and
 *  {@code /rejoin} re-claims it. The lines read as hypixel's party lines do: yellow, names in aqua, no tag. */
public final class LobbyCommands {

    private LobbyCommands() {}

    public static void register() {
        Sessions.register(LobbyCommands::gameDeparture);
        AppCommand cmd = Commands.player("spawn", "Back to the lobby from anywhere.", "lobby", "l")
                .usage("/lobby [lobby]", "your lobby, or a game's: bedwars (bw), duels (duel)");
        cmd.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Queues.leave(p);
            if (Sessions.depart(p)) return;
            upward.accept(p);
            Matches.toLobby(p);
        });
        var named = ArgumentType.Word("lobby");
        named.setSuggestionCallback(tennacle.commands.Completions.of(() -> DESTINATIONS.stream().map(Destination::id).toList()));
        cmd.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            String word = ctx.get(named).toLowerCase(java.util.Locale.ROOT);
            Destination to = DESTINATIONS.stream().filter(d -> d.id().equals(word) || d.aliases().contains(word)).findFirst().orElse(null);
            if (to == null) {
                Msg.fail(p, "No lobby named ", Msg.v(word), ". Lobbies: "
                        + String.join(", ", DESTINATIONS.stream().map(Destination::id).toList()) + ".");
                return;
            }
            to.prefer().accept(p);
            leave(p);
        }, named);
        Commands.register(cmd);

        // the results card's (REMATCH) already points here; re-queueing the pair is still to be built
        AppCommand rematch = Commands.player("rematch", "Play the last match again.");
        rematch.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) Msg.fail(p, "Rematches aren't in yet.");
        });
        Commands.register(rematch);

        AppCommand games = Commands.player("games", "Your games: the one you are in, and the ones holding a seat for you.");
        games.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Game in = Game.of(p);
            if (in != null) p.sendMessage(yellow("Playing ").append(gameLine(in, p)).append(yellow(".")));
            List<Game> held = Matches.grotto().reclaimables(p);
            if (held.isEmpty()) {
                if (in == null) p.sendMessage(yellow("You have no games to get back to."));
                return;
            }
            p.sendMessage(yellow("Your games:"));
            for (Game game : held) {
                p.sendMessage(gameLine(game, p).append(Component.text(" ")).append(rejoinButton(game)));
            }
        });
        Commands.register(games);

        AppCommand rejoin = Commands.player("rejoin", "Reclaim the seat a game is holding for you.", "rej")
                .usage("/rejoin [code]", "rejoin your game, or the one with that code (see /games)");
        var code = ArgumentType.Word("code");
        rejoin.setDefaultExecutor((sender, ctx) -> rejoin(sender, null));
        rejoin.addSyntax((sender, ctx) -> rejoin(sender, ctx.get(code)), code);
        Commands.register(rejoin);
    }

    private record Destination(String id, List<String> aliases, Consumer<Player> prefer) {}

    private static final List<Destination> DESTINATIONS = new CopyOnWriteArrayList<>();
    private static volatile Consumer<Player> upward = player -> {};

    /** A lobby {@code /lobby <id>} reaches; {@code prefer} makes it where the leave sends the player. */
    public static void destination(@NotNull String id, @NotNull List<String> aliases, @NotNull Consumer<Player> prefer) {
        DESTINATIONS.removeIf(d -> d.id().equals(id));
        DESTINATIONS.add(new Destination(id, List.copyOf(aliases), prefer));
    }

    /** What a bare {@code /lobby} from a lobby does before sending them home: the lobbies step them up to the main one. */
    public static void upward(@NotNull Consumer<Player> step) {
        upward = step;
    }

    // every way out ends where the lobbies route home, so a named lobby is one trip, never a stop at home first
    private static void leave(Player p) {
        Queues.leave(p);
        if (!Sessions.depart(p)) Matches.toLobby(p);
    }

    // a seat holds through other games, so several may hold at once: a code picks, /games shows them
    private static void rejoin(CommandSender sender, @Nullable String code) {
        if (!(sender instanceof Player p)) return;
        if (Matches.grotto().engaged(p)) {
            Msg.fail(p, "You're already in a game.");
            return;
        }
        List<Game> held = Matches.grotto().reclaimables(p);
        if (held.isEmpty()) {
            Msg.fail(p, "No seat is being held for you.");
            return;
        }
        if (code == null && held.size() > 1) {
            p.sendMessage(yellow("Several games are holding a seat for you. Use ").append(command("/games")).append(yellow(" to pick one.")));
            return;
        }
        Game pick = code == null ? held.getFirst()
                : held.stream().filter(g -> g.code().equalsIgnoreCase(code)).findFirst().orElse(null);
        if (pick == null) {
            p.sendMessage(yellow("That game isn't holding a seat for you. Use ").append(command("/games")).append(yellow(" to see yours.")));
            return;
        }
        Queues.dequeue(p);
        Spectators.exit(p);
        KitLayouts.cancel(p);
        if (!pick.join(p)) { // the game closed, or the held seat went, between the pick and here
            p.sendMessage(yellow("That seat is gone."));
            return;
        }
        p.sendMessage(yellow("Rejoining ").append(gameLine(pick, p)).append(yellow("...")));
    }

    /** The game as a player knows it: its title, their teammates, their opponents - never the code, which rides the click. */
    static Component gameLine(Game game, Player me) {
        Mode mode = Matches.modeOf(game);
        // a game of many sides names nobody: who else waits is theirs to show, and a duel's foe is the line's point
        if (mode != null && mode.family() == Mode.Family.GAME) return aqua(mode.title());
        Slot mine = game.roster().slotOf(me);
        if (mine == null) mine = game.roster().reservedSlotOf(me.getUsername());
        String team = mine != null ? mine.team() : "";
        List<String> mates = new ArrayList<>();
        List<String> foes = new ArrayList<>();
        for (Player member : game.members()) {
            if (member != me) (Match.teamOf(game, member).equals(team) ? mates : foes).add(member.getUsername());
        }
        for (String held : game.roster().reserved()) {
            if (held.equals(me.getUsername())) continue;
            Slot seat = game.roster().reservedSlotOf(held);
            (seat != null && seat.team().equals(team) ? mates : foes).add(held);
        }
        Component line = aqua(mode != null ? mode.title() : game.type().id());
        if (!mates.isEmpty()) line = line.append(yellow(" with ")).append(names(mates));
        if (!foes.isEmpty()) line = line.append(yellow(" vs ")).append(names(foes));
        return line;
    }

    private static Component names(List<String> names) {
        Component out = Component.empty();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) out = out.append(yellow(", "));
            out = out.append(aqua(names.get(i)));
        }
        return out;
    }

    // scrims' [Accept], for a seat: bold green, runs the rejoin
    private static Component rejoinButton(Game game) {
        return Msg.THEME.good("[Rejoin]").decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/rejoin " + game.code()))
                .hoverEvent(HoverEvent.showText(yellow("Click to rejoin!")));
    }

    private static Component command(String command) {
        return aqua(command).clickEvent(ClickEvent.runCommand(command)).hoverEvent(HoverEvent.showText(yellow("Click to run")));
    }

    private static Component yellow(String text) {
        return Component.text(text, NamedTextColor.YELLOW);
    }

    private static Component aqua(String text) {
        return Component.text(text, NamedTextColor.AQUA);
    }

    private static Runnable gameDeparture(Player p) {
        if (seatedIn(p) == null && Matches.grotto().spectating(p) == null) return null;
        return () -> {
            leaveGame(p);
            Matches.toLobby(p);
        };
    }

    /**
     * Takes {@code p} out of the game they sit in, haunt or watch, with the line {@code /lobby} says; the caller
     * routes them. {@code false} when they were in none.
     */
    public static boolean leaveGame(@NotNull Player p) {
        Game game = seatedIn(p);
        if (game == null) {
            if (Matches.grotto().spectating(p) == null) return false;
            Spectators.exit(p);
            return true;
        }
        Game.Departure departure = game.depart(p);
        // the hold as it stands after the leave: a seat put out on the way (no bed to come back to) holds nothing
        if (departure != null && departure.reserved() && game.roster().reservedSlotOf(p.getUsername()) != null) {
            // the click puts the rejoin in the chat box, this game's code already on it
            String rejoin = "/rejoin " + game.code();
            p.sendMessage(yellow("You left ").append(gameLine(game, p)).append(yellow(". Click to rejoin."))
                    .clickEvent(ClickEvent.suggestCommand(rejoin))
                    .hoverEvent(HoverEvent.showText(yellow("Puts ").append(aqua(rejoin)).append(yellow(" in your chat")))));
        } else {
            p.sendMessage(yellow("You left ").append(gameLine(game, p)).append(yellow(".")));
        }
        return true;
    }

    /** The game {@code p} sits in or haunts, bound or not. */
    public static @Nullable Game seatedIn(@NotNull Player p) {
        Game seated = Game.of(p);
        if (seated != null) return seated;
        // a ghost is unbound, and so is a player whose join bind never landed: the tag can't route either,
        // but the roster still holds their seat - without this they cannot leave the game at all
        for (Game game : Matches.grotto().games()) {
            if (game.status() != Game.Status.OPEN) continue;
            if (game.isGhost(p) || game.roster().slotOf(p) != null) return game;
        }
        return null;
    }
}
