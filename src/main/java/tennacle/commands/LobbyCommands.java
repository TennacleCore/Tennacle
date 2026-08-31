package tennacle.commands;

import io.github.term4.grotto.Game;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.entity.Player;
import tennacle.Msg;
import tennacle.games.Duels;

/** {@code /spawn} (aliases {@code /lobby}, {@code /l}): back to the lobby from anywhere. A seated player
 *  departs their game - the seat follows the resolved reclaim rule, and {@code /rejoin} re-claims it. */
public final class LobbyCommands {

    private LobbyCommands() {}

    public static void register() {
        Command cmd = new Command("spawn", "lobby", "l");
        cmd.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Game seated = Game.of(p);
            if (seated == null) {
                for (Game game : Duels.grotto().games()) { // ghosts are unbound - the tag can't route them
                    if (game.isGhost(p)) {
                        seated = game;
                        break;
                    }
                }
            }
            if (seated != null) {
                Game.Departure departure = seated.depart(p);
                if (departure != null && departure.reserved()) {
                    Msg.tag(p, "lobby", "left the game - your seat holds, ", Msg.run("/rejoin"));
                } else {
                    Msg.tag(p, "lobby", "left the game");
                }
            } else {
                for (Game game : Duels.grotto().games()) {
                    if (game.spectators().contains(p)) game.unspectate(p);
                }
            }
            Duels.toLobby(p);
        });
        MinecraftServer.getCommandManager().register(cmd);

        Command rejoin = new Command("rejoin");
        rejoin.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            if (Game.of(p) != null) {
                Msg.err(p, "lobby", "you're already in a game");
                return;
            }
            Game held = Duels.grotto().reclaimable(p);
            if (held == null) {
                Msg.err(p, "lobby", "no seat is being held for you");
                return;
            }
            held.join(p);
            Msg.tag(p, "lobby", "rejoining your " + held.type().id());
        });
        MinecraftServer.getCommandManager().register(rejoin);
    }
}
