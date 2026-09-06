package tennacle.commands;

import tennacle.games.watch.Spectators;
import tennacle.Msg;
import tennacle.Sessions;
import tennacle.games.Matches;

import io.github.term4.grotto.Game;
import net.minestom.server.entity.Player;

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

        AppCommand rejoin = Commands.player("rejoin", "Reclaim the seat a game is holding for you.");
        rejoin.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            if (Game.of(p) != null) {
                Msg.fail(p, "You're already in a game.");
                return;
            }
            Game held = Matches.grotto().reclaimable(p);
            if (held == null) {
                Msg.fail(p, "No seat is being held for you.");
                return;
            }
            held.join(p);
            Msg.ok(p, "Rejoining your ", Msg.v(held.type().id()), ".");
        });
        Commands.register(rejoin);
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
                    Msg.say(p, "You left the game. Your seat holds: ", Msg.run("/rejoin"), ".");
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
