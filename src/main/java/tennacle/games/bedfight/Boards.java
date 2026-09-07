package tennacle.games.bedfight;

import tennacle.looks.Frames;
import tennacle.looks.Palette;

import io.github.term4.grotto.modules.view.Scoreboard;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.modules.outcome.LastStanding;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
        b.line((g, me) -> clockLine(g), Beds.FALLS_AT, Beds.STANDS)
                .blank()
                .line((g, me) -> sideLine(g, teamAt(g, 0), me), Beds.STANDS, LastStanding.OUT, Respawns.DEAD)
                .line((g, me) -> sideLine(g, teamAt(g, 1), me), Beds.STANDS, LastStanding.OUT, Respawns.DEAD);
    }

    private static Component clockLine(Game game) {
        // the clock is only news while a bed stands; players can beat it to every one of them
        if (Beds.standing(game).isEmpty()) return Component.text("Beds Destroyed", NamedTextColor.RED);
        long left = Beds.fallsIn(game);
        if (left < 0) return Component.empty();
        return Component.text("Bed Destruction in ", NamedTextColor.WHITE)
                .append(Component.text(clock(left), Frames.timeColor(left, game.rules()
                        .resolve(io.github.term4.grotto.state.Scope.GAME, Beds.DESTROY))));
    }

    /**
     * One side: its color's initial in bold, then the name, then a standing bed, how many of a bedless side are
     * still in, or the cross that says they are out. The viewer's own side is marked.
     */
    private static Component sideLine(Game game, String team, Player viewer) {
        if (team == null) return Component.empty();
        Team side = game.roster().team(team);
        Component line = Component.text(Palette.letter(side), side.color(), TextDecoration.BOLD)
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
