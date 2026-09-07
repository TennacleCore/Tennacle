package tennacle.games.bedfight;

import tennacle.games.Match;
import tennacle.looks.Frames;
import tennacle.looks.Palette;

import io.github.term4.grotto.modules.view.Scoreboard;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.modules.outcome.LastStanding;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.Scope;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.entity.Player;

import java.util.List;

/**
 * Bed fight's board rows, the bedwars shape a full implementation will reuse: the clock to bed destruction,
 * then a line a side - its color's initial, its name, and one glyph for how it stands.
 */
final class Boards {

    private Boards() {}

    private static final Component BED = Component.text("✔", NamedTextColor.GREEN);
    private static final Component GONE = Component.text("✖", NamedTextColor.RED);
    // captured hypixel: the lighter glyphs, and bold
    private static final Component BED_LOUD = Component.text("✓", NamedTextColor.GREEN).decorate(TextDecoration.BOLD);
    private static final Component GONE_LOUD = Component.text("✗", NamedTextColor.RED).decorate(TextDecoration.BOLD);

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
                .line((g, me) -> sideLine(g, teamAt(g, 0), me, BED_LOUD, GONE_LOUD), Beds.STANDS, LastStanding.OUT, Respawns.DEAD)
                .line((g, me) -> sideLine(g, teamAt(g, 1), me, BED_LOUD, GONE_LOUD), Beds.STANDS, LastStanding.OUT, Respawns.DEAD)
                .blank()
                .line((g, me) -> Frames.hypixelRow("Kills: ", kills(g, Match.viewed(g, me))), BedFight.KILLS));
    }

    // uncaptured (the minemen bed fight capture is empty): the minemen frame around the scrims rows
    static Scoreboard minemen() {
        return Frames.minemen("Bed Fight", b -> b
                .line((g, me) -> sideLine(g, teamAt(g, 0), me, BED, GONE), Beds.STANDS, LastStanding.OUT, Respawns.DEAD)
                .line((g, me) -> sideLine(g, teamAt(g, 1), me, BED, GONE), Beds.STANDS, LastStanding.OUT, Respawns.DEAD)
                .blank()
                .line((g, me) -> Frames.minemenRow("Kills: ", kills(g, Match.viewed(g, me))), BedFight.KILLS)
                .blank()
                .line((g, me) -> clockLine(g, "Bed gone in ")));
    }

    private static void rows(Scoreboard b) {
        b.line((g, me) -> clockLine(g, "Bed gone in ")) // no watched key: a clock is the board's own timer to refresh
                .blank()
                .line((g, me) -> sideLine(g, teamAt(g, 0), me, BED, GONE), Beds.STANDS, LastStanding.OUT, Respawns.DEAD)
                .line((g, me) -> sideLine(g, teamAt(g, 1), me, BED, GONE), Beds.STANDS, LastStanding.OUT, Respawns.DEAD);
    }

    private static int kills(Game game, Player viewer) {
        Slot seat = viewer != null ? game.roster().slotOf(viewer) : null;
        return seat != null ? game.state().get(Scope.slot(seat.index()), BedFight.KILLS) : 0;
    }

    // no clock, or nothing left for one to take: the match's own stopwatch, counting up
    private static Component clockLine(Game game, String label) {
        long full = game.rules().resolve(Scope.GAME, Beds.DESTROY);
        if (full < 0 || Beds.standing(game).isEmpty()) {
            return row("Time: ", Component.text(clock(Match.playTime(game)), NamedTextColor.GREEN));
        }
        long left = Beds.fallsIn(game);
        if (left < 0) left = full; // the opener: the clock arms with the live phase and reads full until it does
        return row(label, Component.text(clock(left), Frames.timeColor(game, left)));
    }

    private static Component row(String label, Component value) {
        return Component.text(label, NamedTextColor.WHITE).append(value);
    }

    /**
     * One side: its color's initial in bold, then the name, then a standing bed, how many of a bedless side are
     * still in, or the cross that says they are out. The viewer's own side is marked.
     */
    private static Component sideLine(Game game, String team, Player viewer, Component bed, Component gone) {
        if (team == null) return Component.empty();
        Team side = game.roster().team(team);
        Component line = Component.text(Palette.letter(side), side.color())
                .append(Component.text(" " + Palette.titled(side) + ": ", NamedTextColor.WHITE))
                .append(state(game, team, bed, gone));
        Slot mine = game.roster().slotOf(viewer);
        return mine != null && mine.team().equals(team)
                ? line.append(Component.text(" YOU", NamedTextColor.GRAY)) : line;
    }

    private static Component state(Game game, String team, Component bed, Component gone) {
        if (Beds.stands(game, team)) return bed;
        LastStanding standing = game.module(LastStanding.class);
        int alive = standing != null ? standing.standing(team) : 0;
        return alive > 0 ? Component.text(alive, NamedTextColor.GREEN) : gone;
    }

    private static String teamAt(Game game, int index) {
        List<Team> teams = game.roster().teams();
        return index < teams.size() ? teams.get(index).id() : null;
    }

    private static String clock(long ticks) {
        long seconds = Math.max(0, ticks) / 20;
        return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
    }
}
