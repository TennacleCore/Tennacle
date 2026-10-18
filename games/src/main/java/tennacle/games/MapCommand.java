package tennacle.games;

import tennacle.Msg;
import tennacle.commands.AppCommand;
import tennacle.commands.Commands;

import io.github.term4.grotto.Game;
import net.minestom.server.entity.Player;

/** {@code /map}: the map of the game you play or watch, in Hypixel's words. */
public final class MapCommand {

    private MapCommand() {}

    public static void register() {
        AppCommand map = Commands.player("map", "The map you're playing on.");
        map.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Game game = Game.of(p);
            if (game == null) game = Matches.grotto().spectating(p);
            if (game == null) {
                Msg.fail(p, "You're not in a game.");
                return;
            }
            Msg.say(p, "You are currently playing on ", Msg.v(game.arena().meta().name()));
        });
        Commands.register(map);
    }
}
