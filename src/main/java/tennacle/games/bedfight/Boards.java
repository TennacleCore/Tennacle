package tennacle.games.bedfight;

import tennacle.games.beds.Beds;
import tennacle.games.beds.Rows;
import tennacle.games.Match;
import tennacle.looks.Frames;

import io.github.term4.grotto.modules.view.Scoreboard;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.modules.outcome.LastStanding;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.Scope;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;

/**
 * Bed fight's board rows, the bedwars shape a full implementation will reuse: the clock to bed destruction,
 * then a line a side - its color's initial, its name, and one glyph for how it stands.
 */
final class Boards {

    private Boards() {}

    static Scoreboard scrims() {
        return Frames.scrims("Bed Fight", Boards::rows);
    }

    static Scoreboard tennacle() {
        return Frames.tennacle("Bed Fight", Boards::rows);
    }

    // captured: the sides' bed state, Kills, and the bed clock in the frame's Time Left row
    static Scoreboard hypixel() {
        return Frames.hypixel("Bed Wars", "Bed Rush Duel", null, b -> b
                .line((g, me) -> clockLine(g, "Time Left: "))
                .blank()
                .rows(Boards::sides, (g, me, i) -> Rows.side(g, teamAt(g, i), me), Beds.STANDS, LastStanding.OUT, Respawns.DEAD)
                .ownBlank()
                .own((g, me) -> Frames.hypixelRow("Kills: ", Match.seatStat(g, me, BedFight.KILLS)), BedFight.KILLS));
    }

    // uncaptured (the minemen bed fight capture is empty): the minemen frame around the scrims rows
    static Scoreboard minemen() {
        return Frames.minemen("Bed Fight", b -> b
                .line((g, me) -> clockLine(g, "Bed gone in "))
                .blank()
                .rows(Boards::sides, (g, me, i) -> Rows.side(g, teamAt(g, i), me), Beds.STANDS, LastStanding.OUT, Respawns.DEAD)
                .ownBlank()
                .own((g, me) -> Frames.minemenRow("Kills: ", Match.seatStat(g, me, BedFight.KILLS)), BedFight.KILLS));
    }

    private static void rows(Scoreboard b) {
        b.line((g, me) -> clockLine(g, "Bed gone in ")) // no watched key: a clock is the board's own timer to refresh
                .blank()
                .rows(Boards::sides, (g, me, i) -> Rows.side(g, teamAt(g, i), me), Beds.STANDS, LastStanding.OUT, Respawns.DEAD);
    }

    // no clock, or nothing left for one to take: the match's own stopwatch, counting up
    private static Component clockLine(Game game, String label) {
        long full = game.rules().resolve(Scope.GAME, Beds.DESTROY);
        if (full < 0 || Beds.standing(game).isEmpty()) {
            return row("Time: ", Component.text(Frames.shortClock(Match.playTime(game)), NamedTextColor.GREEN));
        }
        long left = Beds.fallsIn(game);
        if (left < 0) left = full; // the opener: the clock arms with the live phase and reads full until it does
        return row(label, Component.text(Frames.shortClock(left), Frames.timeColor(game, left)));
    }

    private static Component row(String label, Component value) {
        return Component.text(label, NamedTextColor.WHITE).append(value);
    }

    private static int sides(Game game) {
        return game.roster().teams().size();
    }

    private static String teamAt(Game game, int index) {
        List<Team> teams = game.roster().teams();
        return index < teams.size() ? teams.get(index).id() : null;
    }

}
