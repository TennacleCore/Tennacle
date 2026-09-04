package tennacle.commands;

import io.github.term4.polyp.platform.compatibility.SpectatorMode;
import io.github.term4.grotto.modules.view.Spectating;
import java.util.List;
import tennacle.Msg;
import tennacle.games.Matches;
import tennacle.games.Spectators;

import io.github.term4.grotto.Game;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;

/**
 * {@code /spectate <player>}: a view of their game without a seat; {@code /l} steps back out.
 * {@code /spectate mode true|ghost}: the client's spectator mode, or the ghost body.
 */
public final class SpectateCommands {

    private SpectateCommands() {}

    public static void register() {
        Command spectate = new Command("spectate", "spec");
        var name = ArgumentType.Word("player");
        var body = ArgumentType.Word("body").from("true", "ghost");
        spectate.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player me)) return;
            if (!Spectators.mode(me, "true".equals(ctx.get(body)) ? SpectatorMode.Mode.TRUE : SpectatorMode.Mode.GHOST)) {
                Msg.err(me, "spectate", "you're not spectating");
            }
        }, ArgumentType.Literal("mode"), body);
        spectate.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player me)) return;
            if (Matches.grotto().engaged(me)) {
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
            if (!Spectating.allowed(open.getFirst())) { Msg.err(me, "spectate", "that game doesn't allow spectators"); return; }
            Spectators.enter(open.getFirst(), me, null);
            Msg.tag(me, "spectate", "watching the " + open.getFirst().type().id() + " - ", Msg.run("/l"), " to leave");
        });
        spectate.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player me)) return;
            if (Matches.grotto().engaged(me)) {
                Msg.err(me, "spectate", "you're in a game - ", Msg.run("/l"), " first");
                return;
            }
            Player target = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(ctx.get(name));
            if (target == null && ctx.get(name).equals("mode")) {
                Msg.tag(me, "spectate", "usage: ", Msg.run("/spectate mode true"), " or ", Msg.run("/spectate mode ghost"));
                return;
            }
            if (target == null) {
                Msg.err(me, "spectate", "no player named ", Msg.v(ctx.get(name)));
                return;
            }
            Game game = Game.of(target);
            if (game == null) game = Matches.grotto().spectating(target); // watching someone: their game is the game
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
            if (!Spectating.allowed(game)) { Msg.err(me, "spectate", "that game doesn't allow spectators"); return; }
            Spectators.enter(game, me, target);
            Msg.tag(me, "spectate", "watching ", Msg.v(target.getUsername()), "'s " + game.type().id() + " - ",
                    Msg.run("/l"), " to leave");
        }, name);
        MinecraftServer.getCommandManager().register(spectate);
    }
}
