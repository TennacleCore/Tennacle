package tennacle.games;

import io.github.term4.barnacle.Arena;
import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.bloom.Teams;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.roster.Team;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.util.RGBLike;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A team's look: {@code display} on the chat wire, {@code armor} for leather, the block it places ({@code family}
 * in {@code hue}) and its {@code section}'s hue. A team is an ordinal ("1", "2", ...) with a default look per
 * ordinal; the arena's {@link Teams team marker} overrides any field of it.
 */
public record Palette(@NotNull TextColor display, @NotNull RGBLike armor, @NotNull String family,
                      @NotNull String hue, @NotNull String section) {

    public static final Palette NEUTRAL = new Palette(NamedTextColor.WHITE, NamedTextColor.WHITE, "terracotta", "white", "white");

    private static final List<Palette> DEFAULTS = List.of(
            dyed(NamedTextColor.RED, NamedTextColor.RED, "red"),
            dyed(NamedTextColor.BLUE, NamedTextColor.BLUE, "blue"),
            dyed(NamedTextColor.GREEN, NamedTextColor.GREEN, "lime"),
            dyed(NamedTextColor.YELLOW, NamedTextColor.YELLOW, "yellow"),
            dyed(NamedTextColor.AQUA, NamedTextColor.AQUA, "cyan"),
            dyed(NamedTextColor.LIGHT_PURPLE, NamedTextColor.LIGHT_PURPLE, "pink"),
            dyed(NamedTextColor.DARK_PURPLE, TextColor.color(0x8B2FBE), "purple"),
            dyed(NamedTextColor.GOLD, TextColor.color(0xE0791E), "orange"));

    private static Palette dyed(TextColor display, RGBLike armor, String hue) {
        return new Palette(display, armor, "terracotta", hue, hue);
    }

    /** The ordinal's default; a non-ordinal or unlisted id is {@link #NEUTRAL}. */
    public static @NotNull Palette defaults(@NotNull String team) {
        int n = ordinal(team);
        return n >= 1 && n <= DEFAULTS.size() ? DEFAULTS.get(n - 1) : NEUTRAL;
    }

    /** The ordinal's default with the arena's team marker laid over it. */
    public static @NotNull Palette of(@NotNull Arena arena, @NotNull String team) {
        Palette p = defaults(team);
        Teams.Spec spec = Teams.of(arena, team);
        if (spec.display() != null) p = new Palette(Teams.color(spec.display()), p.armor, p.family, p.hue, p.section);
        if (spec.armor() != null) p = new Palette(p.display, Teams.color(spec.armor()), p.family, p.hue, p.section);
        if (spec.blocks() != null) {
            String[] blocks = Teams.blocks(spec.blocks());
            p = new Palette(p.display, p.armor, blocks[0], blocks[1], p.section);
        }
        if (spec.section() != null) p = new Palette(p.display, p.armor, p.family, p.hue, spec.section());
        return p;
    }

    public static @NotNull Palette of(@NotNull Game game, @NotNull String team) {
        return of(game.arena(), team);
    }

    /** The arena's teams - the groups of its {@code seats} markers in ordinal order - each in its palette. */
    public static @NotNull List<Team> teams(@NotNull Arena arena, @NotNull MarkerKey<?> seats) {
        List<String> ids = new ArrayList<>(arena.groups(seats));
        ids.sort(Comparator.comparingInt(Palette::ordinal).thenComparing(Comparator.naturalOrder()));
        List<Team> teams = new ArrayList<>();
        for (String id : ids) {
            if (id.isEmpty()) continue;
            Palette p = of(arena, id);
            teams.add(new Team(id, p.display, p.armor));
        }
        return teams;
    }

    /** The block this team places. */
    public @NotNull Material blocks() {
        Material material = Material.fromKey("minecraft:" + hue + "_" + family);
        return material != null ? material : Material.WHITE_TERRACOTTA;
    }

    /** The display color's name ("RED", "LIGHT PURPLE") - the networks print a team as its color's name. */
    public @NotNull String name() {
        return NamedTextColor.nearestTo(display).toString().toUpperCase(Locale.ROOT).replace('_', ' ');
    }

    public @NotNull String letter() {
        return name().substring(0, 1);
    }

    /** The dye a cage's authored RED_* blocks take ("LIME"). */
    public @NotNull String dye() {
        return hue.toUpperCase(Locale.ROOT);
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
