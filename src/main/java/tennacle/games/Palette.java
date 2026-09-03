package tennacle.games;

import io.github.term4.barnacle.Arena;
import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.bloom.Teams;
import io.github.term4.canvas.theme.Livery;
import io.github.term4.grotto.roster.Team;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The liveries a match seats: a team is an ordinal ("1", "2", ...) read through three layers - the arena's
 * {@link Teams team marker}, the game's own defaults, the ordinal's convention. Roles are declared where they are
 * read: a kit's {@code armor.helmet} and {@code blocks.1}, bridge's {@code cage}, a name's {@code display}.
 */
public final class Palette {

    private Palette() {}

    /** Checked against every declared role: a value a reader cannot parse fails the launch here, not a kit mid-match. */
    public static @NotNull Livery livery(@NotNull Arena arena, @NotNull String team, @NotNull Livery game) {
        Livery livery = new Livery(Teams.of(arena, team).values()).over(game).over(Livery.ordinal(team));
        livery.check();
        return livery;
    }

    /** The arena's teams - the groups of its {@code seats} markers in ordinal order - each in its livery. */
    public static @NotNull List<Team> teams(@NotNull Arena arena, @NotNull MarkerKey<?> seats, @NotNull Livery game) {
        List<String> ids = new ArrayList<>(arena.groups(seats));
        ids.sort(Comparator.comparingInt(Palette::ordinal).thenComparing(Comparator.naturalOrder()));
        List<Team> teams = new ArrayList<>();
        for (String id : ids) {
            if (!id.isEmpty()) teams.add(new Team(id, livery(arena, id, game)));
        }
        return teams;
    }

    /** The team as the networks print it: its display color's name ("RED", "LIGHT PURPLE"). */
    public static @NotNull String name(@NotNull Team team) {
        return team.livery().get(Team.DISPLAY).named().toString().toUpperCase(Locale.ROOT).replace('_', ' ');
    }

    public static @NotNull String letter(@NotNull Team team) {
        return name(team).substring(0, 1);
    }

    // a non-ordinal id sorts after every ordinal
    private static int ordinal(String team) {
        try {
            return Integer.parseInt(team);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
