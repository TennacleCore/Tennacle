package tennacle.commands;

import java.util.List;
import tennacle.Msg;
import tennacle.games.Matches;
import tennacle.games.Spectators;

import io.github.term4.grotto.Game;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;

/** {@code /spectate <player>}: a view of their game without a seat; {@code /l} steps back out. */
public final class SpectateCommands {

    private SpectateCommands() {}

    public static void register() {
        Command spectate = new Command("spectate", "spec");
        var name = ArgumentType.Word("player");
        spectate.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player me)) return;
            if (Game.of(me) != null) {
                Msg.err(me, "spectate", "you're in a game - ", Msg.run("/l"), " first");
                return;
            }
            List<Game> open = Matches.grotto().games().stream().filter(g -> g.status() == Game.Status.OPEN).toList();
            if (open.size() != 1) {
                Msg.tag(me, "spectate", "usage: ", Msg.run("/spectate <player>"),
                        open.isEmpty() ? " - no game is running" : " - " + open.size() + " games are running");
                return;
            }
            Spectators.exit(me);
            Spectators.enter(open.getFirst(), me, null);
            Msg.tag(me, "spectate", "watching the " + open.getFirst().type().id() + " - ", Msg.run("/l"), " to leave");
        });
        spectate.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player me)) return;
            if (Game.of(me) != null) {
                Msg.err(me, "spectate", "you're in a game - ", Msg.run("/l"), " first");
                return;
            }
            Player target = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(ctx.get(name));
            if (target == null) {
                Msg.err(me, "spectate", "no player named ", Msg.v(ctx.get(name)));
                return;
            }
            Game game = Game.of(target);
            if (game == null) {
                for (Game candidate : Matches.grotto().games()) { // a ghost is unbound - the tag can't route them
                    if (candidate.isGhost(target)) {
                        game = candidate;
                        break;
                    }
                }
            }
            if (game == null || game.status() != Game.Status.OPEN) {
                Msg.err(me, "spectate", Msg.v(target.getUsername()), " is not in a game");
                return;
            }
            Spectators.exit(me); // a view elsewhere ends first
            Spectators.enter(game, me, target);
            Msg.tag(me, "spectate", "watching ", Msg.v(target.getUsername()), "'s " + game.type().id() + " - ",
                    Msg.run("/l"), " to leave");
        }, name);
        MinecraftServer.getCommandManager().register(spectate);
    }
}
