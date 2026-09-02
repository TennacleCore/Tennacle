package tennacle.games;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.util.RGBLike;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * The one place a team's LOOK lives: chat colour, leather dye, and the wool/terracotta a builder places. A game
 * names its sides ({@code "red"}, {@code "blue"}, or {@code "purple"}, {@code "orange"}) and everything - the team
 * declaration, the kit blocks, the boards - reads the side from here, so swapping a map's sides is one edit, not a
 * sweep. Maps carry the side name on their markers; an unlisted name falls back to white.
 */
public final class Palette {

    /** A side's presentation: {@code color} on the 1.8 wire (named), {@code dye} RGB for leather, {@code block} placed. */
    public record Side(@NotNull TextColor color, @NotNull RGBLike dye, @NotNull Material block) {}

    private static final Side DEFAULT = new Side(NamedTextColor.WHITE, NamedTextColor.WHITE, Material.WHITE_TERRACOTTA);

    private static final Map<String, Side> SIDES = Map.ofEntries(
            Map.entry("red", new Side(NamedTextColor.RED, NamedTextColor.RED, Material.RED_TERRACOTTA)),
            Map.entry("blue", new Side(NamedTextColor.BLUE, NamedTextColor.BLUE, Material.BLUE_TERRACOTTA)),
            Map.entry("green", new Side(NamedTextColor.GREEN, NamedTextColor.GREEN, Material.LIME_TERRACOTTA)),
            Map.entry("yellow", new Side(NamedTextColor.YELLOW, NamedTextColor.YELLOW, Material.YELLOW_TERRACOTTA)),
            Map.entry("aqua", new Side(NamedTextColor.AQUA, NamedTextColor.AQUA, Material.CYAN_TERRACOTTA)),
            Map.entry("pink", new Side(NamedTextColor.LIGHT_PURPLE, NamedTextColor.LIGHT_PURPLE, Material.PINK_TERRACOTTA)),
            Map.entry("purple", new Side(NamedTextColor.DARK_PURPLE, TextColor.color(0x8B2FBE), Material.PURPLE_TERRACOTTA)),
            Map.entry("orange", new Side(NamedTextColor.GOLD, TextColor.color(0xE0791E), Material.ORANGE_TERRACOTTA)));

    private Palette() {}

    public static @NotNull Side of(@NotNull String side) {
        return SIDES.getOrDefault(side, DEFAULT);
    }

    /** Whether {@code side} is a known named side (an arena's marker group can name any of these). */
    public static boolean has(@NotNull String side) {
        return SIDES.containsKey(side);
    }

    /** The two-sided default in order - what a 1v1 or a bridge duel declares unless a map says otherwise. */
    public static @NotNull List<String> versus() {
        return List.of("red", "blue");
    }
}
