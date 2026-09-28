package tennacle.games.beds;

import tennacle.looks.Palette;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.outcome.LastStanding;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A bed mode's board row per side: its color's initial, its name, and one glyph for how it stands. */
public final class Rows {

    public static final Component BED = Component.text("✔", NamedTextColor.GREEN);
    public static final Component GONE = Component.text("✖", NamedTextColor.RED);
    // captured hypixel: the lighter glyphs, and bold
    public static final Component BED_LOUD = Component.text("✓", NamedTextColor.GREEN).decorate(TextDecoration.BOLD);
    public static final Component GONE_LOUD = Component.text("✗", NamedTextColor.RED).decorate(TextDecoration.BOLD);

    private Rows() {}

    /**
     * One side: its color's initial, then the name, then a standing bed, how many of a bedless side are still
     * in, or the cross that says they are out. The viewer's own side is marked.
     */
    public static @NotNull Component side(@NotNull Game game, @Nullable String team, @Nullable Player viewer,
                                          @NotNull Component bed, @NotNull Component gone) {
        if (team == null) return Component.empty();
        Team side = game.roster().team(team);
        Component line = Component.text(Palette.letter(side), side.color())
                .append(Component.text(" " + Palette.titled(side) + ": ", NamedTextColor.WHITE))
                .append(state(game, team, bed, gone));
        Slot mine = viewer != null ? game.roster().slotOf(viewer) : null;
        return mine != null && mine.team().equals(team)
                ? line.append(Component.text(" YOU", NamedTextColor.GRAY)) : line;
    }

    private static Component state(Game game, String team, Component bed, Component gone) {
        if (Beds.stands(game, team)) return bed;
        LastStanding standing = game.module(LastStanding.class);
        int alive = standing != null ? standing.standing(team) : 0;
        return alive > 0 ? Component.text(alive, NamedTextColor.GREEN) : gone;
    }
}
