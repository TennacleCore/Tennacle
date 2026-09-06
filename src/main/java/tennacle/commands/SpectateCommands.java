package tennacle.commands;

import tennacle.games.watch.Spectators;
import tennacle.games.Mode;
import io.github.term4.polyp.platform.compatibility.SpectatorMode;
import io.github.term4.polyp.platform.player.PlayerNames;
import io.github.term4.grotto.modules.view.Spectating;
import java.util.List;
import tennacle.Msg;
import tennacle.games.Matches;

import io.github.term4.grotto.Game;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;

/**
 * {@code /spectate <player>}: a view of their game without a seat; {@code /l} steps back out.
 * {@code /spectate mode true|ghost}: the client's spectator mode, or the ghost body.
 */
public final class SpectateCommands {

    private SpectateCommands() {}

    public static void register() {
        AppCommand spectate = Commands.player("spectate", "Watch a running game.", "spec")
                .usage("/spectate <player>", "their game, from their side")
                .usage("/spectate mode true|ghost", "the client's spectator mode, or the ghost body")
                .featured();
        var name = ArgumentType.Word("player");
        name.setSuggestionCallback(PlayerNames.suggest());
        var body = ArgumentType.Word("body").from("true", "ghost");
        spectate.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player me)) return;
            if (!Spectators.mode(me, "true".equals(ctx.get(body)) ? SpectatorMode.Mode.TRUE : SpectatorMode.Mode.GHOST)) {
                Msg.fail(me, "You're not spectating.");
            }
        }, ArgumentType.Literal("mode"), body);
        spectate.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player me)) return;
            if (Matches.grotto().engaged(me)) {
                Msg.fail(me, "You're in a game. ", Msg.run("/l"), " first.");
                return;
            }
            List<Game> open = Matches.grotto().games().stream().filter(g -> g.status() == Game.Status.OPEN).toList();
            if (open.isEmpty()) {
                Msg.fail(me, "No game is running.");
                return;
            }
            if (open.size() != 1) {
                Msg.say(me, open.size() + " games are running. ", Msg.run("/spectate <player>"), " picks one.");
                return;
            }
            Spectators.exit(me);
            if (!Spectating.allowed(open.getFirst())) { Msg.fail(me, "That game doesn't allow spectators."); return; }
            Spectators.enter(open.getFirst(), me, null);
            Msg.ok(me, "Watching the ", Msg.v(open.getFirst().type().id()), ". ", Msg.run("/l"), " to leave.");
        });
        spectate.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player me)) return;
            if (Matches.grotto().engaged(me)) {
                Msg.fail(me, "You're in a game. ", Msg.run("/l"), " first.");
                return;
            }
            Player target = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(ctx.get(name));
            if (target == null && ctx.get(name).equals("mode")) {
                Commands.usage(me, "spectate");
                return;
            }
            if (target == null) {
                Msg.fail(me, "No player named ", Msg.v(ctx.get(name)), ".");
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
                Msg.fail(me, Msg.v(target.getUsername()), " is not in a game.");
                return;
            }
            Spectators.exit(me); // a view elsewhere ends first
            if (!Spectating.allowed(game)) { Msg.fail(me, "That game doesn't allow spectators."); return; }
            Spectators.enter(game, me, target);
            Msg.ok(me, "Watching ", Msg.v(target.getUsername()), "'s ", Msg.v(game.type().id()), ". ", Msg.run("/l"), " to leave.");
        }, name);
        Commands.register(spectate);
    }
}
