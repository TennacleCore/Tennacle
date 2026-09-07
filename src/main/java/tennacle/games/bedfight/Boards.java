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
import net.minestom.server.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * Bed fight's board rows, the bedwars shape a full implementation will reuse: the clock to bed destruction,
 * then a line a side - its color's initial, its name, and one glyph for how it stands.
 */
final class Boards {

    private Boards() {}

    private static final Component BED = Component.text("✔", NamedTextColor.GREEN);
    private static final Component GONE = Component.text("✖", NamedTextColor.RED);

    static Scoreboard scrims() {
        return Frames.scrims("Bed Fight", Boards::rows);
    }

    static Scoreboard tennacle() {
        return Frames.tennacle("Bed Fight", Boards::rows);
    }

    private static void rows(Scoreboard b) {
        b.line((g, me) -> clockLine(g)) // no watched key: a clock is the board's own timer to refresh
                .blank()
                .line((g, me) -> sideLine(g, teamAt(g, 0), me), Beds.STANDS, LastStanding.OUT, Respawns.DEAD)
                .line((g, me) -> sideLine(g, teamAt(g, 1), me), Beds.STANDS, LastStanding.OUT, Respawns.DEAD);
    }

    // no clock, or nothing left for one to take: the match's own stopwatch, counting up
    private static Component clockLine(Game game) {
        long full = game.rules().resolve(Scope.GAME, Beds.DESTROY);
        if (full < 0 || Beds.standing(game).isEmpty()) {
            return Component.text("Time: ", NamedTextColor.WHITE)
                    .append(Component.text(clock(Match.playTime(game)), NamedTextColor.GREEN));
        }
        long left = Beds.fallsIn(game);
        if (left < 0) left = full; // the opener: the clock arms with the live phase and reads full until it does
        return Component.text("Bed gone in ", NamedTextColor.WHITE)
                .append(Component.text(clock(left), Frames.timeColor(game, left)));
    }

    /**
     * One side: its color's initial in bold, then the name, then a standing bed, how many of a bedless side are
     * still in, or the cross that says they are out. The viewer's own side is marked.
     */
    private static Component sideLine(Game game, String team, Player viewer) {
        if (team == null) return Component.empty();
        Team side = game.roster().team(team);
        Component line = Component.text(Palette.letter(side), side.color())
                .append(Component.text(" " + titled(Palette.name(side)) + ": ", NamedTextColor.WHITE))
                .append(state(game, team));
        Slot mine = game.roster().slotOf(viewer);
        return mine != null && mine.team().equals(team)
                ? line.append(Component.text(" YOU", NamedTextColor.GRAY)) : line;
    }

    private static Component state(Game game, String team) {
        if (Beds.stands(game, team)) return BED;
        LastStanding standing = game.module(LastStanding.class);
        int alive = standing != null ? standing.standing(team) : 0;
        return alive > 0 ? Component.text(alive, NamedTextColor.GREEN) : GONE;
    }

    /** "LIGHT PURPLE" as a board prints it. */
    private static String titled(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (String word : name.split(" ")) {
            if (!out.isEmpty()) out.append(' ');
            out.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
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
