package tennacle.commands;

import tennacle.games.watch.Spectators;
import tennacle.Msg;
import tennacle.Sessions;
import tennacle.games.KitLayouts;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** {@code /spawn} (aliases {@code /lobby}, {@code /l}): back to the lobby from anywhere. A seated player
 *  departs their game - the seat follows the resolved reclaim rule, {@code /games} lists what holds one, and
 *  {@code /rejoin} re-claims it. The lines read as hypixel's party lines do: yellow, names in aqua, no tag. */
public final class LobbyCommands {

    private LobbyCommands() {}

    public static void register() {
        Sessions.register(LobbyCommands::gameDeparture);
        AppCommand cmd = Commands.player("spawn", "Back to the lobby from anywhere.", "lobby", "l");
        cmd.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            if (!Sessions.depart(p)) Matches.toLobby(p);
        });
        Commands.register(cmd);

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

        AppCommand rejoin = Commands.player("rejoin", "Reclaim the seat a game is holding for you.")
                .usage("/rejoin [code]", "the one game holding one, or the one with that board code (/games lists them)");
        var code = ArgumentType.Word("code");
        rejoin.setDefaultExecutor((sender, ctx) -> rejoin(sender, null));
        rejoin.addSyntax((sender, ctx) -> rejoin(sender, ctx.get(code)), code);
        Commands.register(rejoin);
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
        pick.join(p);
        p.sendMessage(yellow("Rejoining ").append(gameLine(pick, p)).append(yellow("...")));
    }

    /** The game as a player knows it: its title, their teammates, their opponents - never the code, which rides the click. */
    static Component gameLine(Game game, Player me) {
        Mode mode = Matches.modeOf(game);
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
        Game seated = Game.of(p);
        if (seated == null) {
            // a ghost is unbound, and so is a player whose join bind never landed: the tag can't route either,
            // but the roster still holds their seat - without this they cannot leave the game at all
            for (Game game : Matches.grotto().games()) {
                if (game.status() != Game.Status.OPEN) continue;
                if (game.isGhost(p) || game.roster().slotOf(p) != null) {
                    seated = game;
                    break;
                }
            }
        }
        if (seated != null) {
            Game game = seated;
            return () -> {
                Game.Departure departure = game.depart(p);
                if (departure != null && departure.reserved()) {
                    // the click puts the rejoin in the chat box, this game's code already on it
                    String rejoin = "/rejoin " + game.code();
                    p.sendMessage(yellow("You left ").append(gameLine(game, p)).append(yellow(". Click to rejoin."))
                            .clickEvent(ClickEvent.suggestCommand(rejoin))
                            .hoverEvent(HoverEvent.showText(yellow("Puts ").append(aqua(rejoin)).append(yellow(" in your chat")))));
                } else {
                    Msg.say(p, "You left the game.");
                }
                Matches.toLobby(p);
            };
        }
        if (Matches.grotto().spectating(p) != null) {
            return () -> {
                Spectators.exit(p);
                Matches.toLobby(p);
            };
        }
        return null;
    }
}
