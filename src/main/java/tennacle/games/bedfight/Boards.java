package tennacle.games.bedfight;

import tennacle.looks.Frames;

import io.github.term4.grotto.modules.view.Scoreboard;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.flow.TimeLimit;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Bed fight's board rows: the clock, each side's bed and how many of it are left, then the players. */
final class Boards {

    private Boards() {}

    static Scoreboard scrims() {
        return Frames.scrims("Bed Fight", Boards::rows);
    }

    static Scoreboard tennacle() {
        return Frames.tennacle("Bed Fight", Boards::rows);
    }

    private static void rows(Scoreboard b) {
        b.line((g, me) -> Component.text("Time: ", NamedTextColor.WHITE)
                        .append(Component.text(clock(TimeLimit.left(g)), Frames.timeColor(g, TimeLimit.left(g)))))
                .blank()
                .line((g, me) -> sideLine(g, teamAt(g, 0)), Beds.STANDS)
                .line((g, me) -> sideLine(g, teamAt(g, 1)), Beds.STANDS)
                .blank()
                .rows(g -> names(g, teamAt(g, 0)))
                .rows(g -> names(g, teamAt(g, 1)));
    }

    // a standing bed is the side's state in one glyph; without one, what is left of the side is the state
    private static Component sideLine(Game game, String team) {
        if (team == null) return Component.empty();
        Team side = game.roster().team(team);
        boolean bed = Beds.stands(game, team);
        Component head = Component.text(team, side.color()).append(Component.text(": ", NamedTextColor.GRAY));
        if (bed) return head.append(Component.text("BED", NamedTextColor.GREEN));
        int alive = alive(game, team);
        return head.append(Component.text(alive + (alive == 1 ? " left" : " left"), NamedTextColor.RED));
    }

    private static int alive(Game game, String team) {
        int n = 0;
        for (Slot seat : game.roster().slots()) {
            if (!seat.team().equals(team)) continue;
            String owner = game.roster().ownerOf(seat);
            if (owner != null && game.memberNamed(owner) != null) n++;
        }
        return n;
    }

    private static List<Component> names(Game game, String team) {
        List<Component> out = new ArrayList<>();
        if (team == null) return out;
        for (Player member : game.members()) {
            Slot seat = game.roster().slotOf(member);
            if (seat == null || !seat.team().equals(team)) continue;
            out.add(Component.text(member.getUsername(), game.roster().team(team).color()));
        }
        return out;
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
