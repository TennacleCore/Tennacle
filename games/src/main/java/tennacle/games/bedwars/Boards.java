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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * The captured Bed Wars board: while the game fills, the map, the seats taken and the count; in play, a row a
 * side and the viewer's kills.
 */
final class Boards {

    private Boards() {}

    /** Captured: the pregame board's last row before the site line. */
    private static final String VERSION = "v1.11.2";

    static Scoreboard hypixel(String mode) {
        return Frames.hypixelGame("Bed Wars", b -> b
                .rows(g -> Match.opening(g) ? 7 : 0, (g, me, i) -> switch (i) {
                    case 0 -> Frames.hypixelRow("Map: ", g.arena().meta().name());
                    case 1 -> Frames.hypixelRow("Players: ", Match.seated(g) + "/" + Match.seats(g));
                    // captured: white "Starting in ", the seconds green
                    case 3 -> Countdown.left(g) < 0 ? Component.text("Waiting...", NamedTextColor.WHITE)
                            : Component.text("Starting in ", NamedTextColor.WHITE)
                                    .append(Component.text(Countdown.left(g) + "s", NamedTextColor.GREEN));
                    case 5 -> Frames.hypixelRow("Mode: ", mode);
                    case 6 -> Frames.hypixelRow("Version: ", VERSION);
                    default -> Component.empty();
                }, Phases.PHASE, Countdown.LEFT)
                .rows(g -> Match.opening(g) ? 0 : 2, (g, me, i) -> i == 1 ? Component.empty() : nextEvent(g))
                .rows(g -> Match.opening(g) ? 0 : g.roster().teams().size(),
                        (g, me, i) -> Rows.side(g, g.roster().teams().get(i).id(), me),
                        Phases.PHASE, Beds.STANDS, LastStanding.OUT, Respawns.DEAD)
                .rows(g -> Match.opening(g) ? 0 : 2, (g, me, i) -> i == 0 ? Component.empty()
                        : Frames.hypixelRow("Kills: ", Match.seatStat(g, me, BedWars.KILLS)), Phases.PHASE, BedWars.KILLS));
    }

    // captured: the next event alone, "Diamond II in 5:22"
    private static Component nextEvent(Game game) {
        Timeline timeline = game.module(Timeline.class);
        Timeline.Next next = timeline != null ? timeline.next() : null;
        return next == null ? Component.empty() : Frames.hypixelRow(next.label() + " in ", Frames.shortClock(next.left()));
    }
}
