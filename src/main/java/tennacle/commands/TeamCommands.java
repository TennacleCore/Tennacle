package tennacle.commands;

import tennacle.Msg;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /team} - which side is which, in seat order. A team is an ordinal and its color is only the convention
 * an unauthored map falls back to, so the two are worth printing together: the id is what {@code /rules @1} and a
 * marker group take, the color is what a player actually sees.
 */
public final class TeamCommands {

    private TeamCommands() {}

    public static void register() {
        AppCommand team = Commands.player("team", "Which side is which, and who is on it.");
        team.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Game game = Game.of(p);
            if (game == null) game = tennacle.games.Matches.grotto().spectating(p);
            if (game == null) {
                Msg.fail(p, "You're not in a game.");
                return;
            }
            Slot mine = game.roster().slotOf(p);
            for (Team side : game.roster().teams()) {
                List<String> members = new ArrayList<>();
                for (int i = 0; game.roster().slot(i) != null; i++) {
                    Slot seat = game.roster().slot(i);
                    String owner = game.roster().ownerOf(seat);
                    if (owner != null && seat.team().equals(side.id())) members.add(owner);
                }
                String who = members.isEmpty() ? "empty" : String.join(", ", members);
                boolean ours = mine != null && mine.team().equals(side.id());
                Msg.say(p, Component.text(side.id(), side.color()), ": ",
                        Msg.v(who), ours ? " (yours)" : "");
            }
        });
        Commands.register(team);
    }
}
