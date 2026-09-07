package tennacle.commands;

import tennacle.games.watch.Spectators;
import tennacle.Msg;
import tennacle.Sessions;
import tennacle.games.KitLayouts;
import tennacle.games.Matches;
import tennacle.games.matchmaking.Queues;

import io.github.term4.grotto.Game;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** {@code /spawn} (aliases {@code /lobby}, {@code /l}): back to the lobby from anywhere. A seated player
 *  departs their game - the seat follows the resolved reclaim rule, and {@code /rejoin} re-claims it. */
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

        AppCommand rejoin = Commands.player("rejoin", "Reclaim the seat a game is holding for you.")
                .usage("/rejoin [code]", "the latest game holding one, or the one with that board code");
        var code = ArgumentType.Word("code");
        rejoin.setDefaultExecutor((sender, ctx) -> rejoin(sender, null));
        rejoin.addSyntax((sender, ctx) -> rejoin(sender, ctx.get(code)), code);
        Commands.register(rejoin);
    }

    // a seat holds through other games, so several may hold at once: the latest game is the default, a code picks
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
        Game pick = code == null ? held.getFirst()
                : held.stream().filter(g -> g.code().equalsIgnoreCase(code)).findFirst().orElse(null);
        if (pick == null) {
            Msg.fail(p, "No seat is held for you in a game ", Msg.v("#" + code), ".");
            return;
        }
        if (code == null && held.size() > 1) {
            Msg.say(p, held.size() + " games hold a seat for you. This is the latest, ", Msg.v(describe(pick, p)), "; the others:");
            for (Game game : held) {
                if (game == pick) continue;
                Msg.say(p, "  ", Msg.link(describe(game, p), "/rejoin " + game.code(), "rejoin this one"));
            }
        }
        Queues.dequeue(p);
        Spectators.exit(p);
        KitLayouts.cancel(p);
        pick.join(p);
        Msg.ok(p, "Rejoining your ", Msg.v(pick.type().id()), " ", Msg.v("#" + pick.code()), ".");
    }

    // the game by its mode and board code, then who else is in it or holds a seat: what a player picks by
    private static String describe(Game game, Player me) {
        List<String> others = new ArrayList<>();
        for (Player member : game.members()) if (member != me) others.add(member.getUsername());
        for (String held : game.roster().reserved()) if (!held.equals(me.getUsername())) others.add(held);
        return game.type().id() + " #" + game.code() + (others.isEmpty() ? "" : " with " + String.join(", ", others));
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
                    Msg.say(p, "You left ", Msg.v(describe(game, p)), ". Your seat holds: ",
                            Msg.link("/rejoin", "/rejoin " + game.code(), "rejoin " + game.type().id() + " #" + game.code()), ".");
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
