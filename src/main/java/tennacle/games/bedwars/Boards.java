package tennacle.games.bedwars;

import tennacle.games.Match;
import tennacle.games.Timeline;
import tennacle.games.beds.Beds;
import tennacle.games.beds.Rows;
import tennacle.looks.Frames;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.modules.flow.Phases;
import io.github.term4.grotto.modules.outcome.LastStanding;
import io.github.term4.grotto.modules.view.Scoreboard;
import io.github.term4.grotto.roster.Roster;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.entity.Player;

/**
 * The captured Bed Wars board: while the game fills, the map, the seats taken and the count; in play, a row a
 * side and the viewer's kills.
 */
final class Boards {

    private Boards() {}

    static Scoreboard hypixel(String mode) {
        return Frames.hypixelGame("Bed Wars", b -> b
                .rows(g -> Match.opening(g) ? 6 : 0, (g, me, i) -> switch (i) {
                    case 0 -> Frames.hypixelRow("Map: ", g.arena().meta().name());
                    case 1 -> Frames.hypixelRow("Players: ", seated(g) + "/" + seats(g));
                    case 3 -> Component.text(Countdown.left(g) < 0 ? "Waiting..." : "Starting in " + Countdown.left(g) + "s",
                            NamedTextColor.WHITE);
                    case 5 -> Frames.hypixelRow("Mode: ", mode);
                    default -> Component.empty();
                }, Phases.PHASE, Countdown.LEFT)
                .rows(g -> Match.opening(g) ? 0 : 2, (g, me, i) -> i == 1 ? Component.empty() : nextEvent(g))
                .rows(g -> Match.opening(g) ? 0 : g.roster().teams().size(),
                        (g, me, i) -> Rows.side(g, g.roster().teams().get(i).id(), me, Rows.BED_LOUD, Rows.GONE_LOUD),
                        Phases.PHASE, Beds.STANDS, LastStanding.OUT, Respawns.DEAD)
                .rows(g -> Match.opening(g) ? 0 : 2, (g, me, i) -> i == 0 ? Component.empty()
                        : Frames.hypixelRow("Kills: ", kills(g, me)), Phases.PHASE, BedWars.KILLS));
    }

    // captured: the next event alone, "Diamond II in 5:22"
    private static Component nextEvent(Game game) {
        Timeline timeline = game.module(Timeline.class);
        Timeline.Next next = timeline != null ? timeline.next() : null;
        return next == null ? Component.empty() : Frames.hypixelRow(next.label() + " in ", Frames.shortClock(next.left()));
    }

    private static int seated(Game game) {
        int seated = 0;
        for (Slot seat : game.roster().slots()) if (game.roster().ownerOf(seat) != null) seated++;
        return seated;
    }

    private static int seats(Game game) {
        int size = game.rules().resolve(Scope.GAME, Roster.TEAM_SIZE);
        return size > 0 ? game.roster().teams().size() * size : game.roster().slots().size();
    }

    private static int kills(Game game, Player viewer) {
        Slot seat = viewer != null ? game.roster().slotOf(viewer) : null;
        return seat != null ? game.state().get(Scope.slot(seat.index()), BedWars.KILLS) : 0;
    }
}
