package tennacle.games;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.util.RGBLike;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * The one place a team's LOOK lives: the chat colour, the leather dye, and the DYE colour of any block it places.
 * A side names a dye ({@code "lime"} for a green team, say - decoupled from the team's own name), and its block in
 * any {@link Family} resolves from that: {@code Palette.of("green").block(Family.WOOL)} is lime wool, {@code .glass()}
 * lime stained glass, and so on for concrete, panes, carpet. A game names its sides on the markers, so swapping a
 * map from red/blue to purple/orange is one edit; an unlisted name falls back to white.
 */
public final class Palette {

    /** A colored block family - {@code minecraft:<dye>_<suffix>}. Modern-only families resolve to null on older data. */
    public enum Family {
        WOOL("wool"), TERRACOTTA("terracotta"), CARPET("carpet"),
        GLASS("stained_glass"), PANE("stained_glass_pane"),
        CONCRETE("concrete"), CONCRETE_POWDER("concrete_powder");

        private final String suffix;
        Family(String suffix) { this.suffix = suffix; }
    }

    /** A side: {@code color} on the 1.8 chat wire (named), {@code dye} RGB for leather, {@code hue} the dye a block takes. */
    public record Side(@NotNull TextColor color, @NotNull RGBLike dye, @NotNull String hue) {
        /** The side's block in {@code family} (lime wool, purple glass, ...); white when the material does not exist. */
        public @NotNull Material block(@NotNull Family family) {
            Material material = Material.fromKey("minecraft:" + hue + "_" + family.suffix);
            return material != null ? material : Material.WHITE_TERRACOTTA;
        }

        public @NotNull Material terracotta() { return block(Family.TERRACOTTA); }
        public @NotNull Material wool() { return block(Family.WOOL); }
    }

    private static final Side DEFAULT = new Side(NamedTextColor.WHITE, NamedTextColor.WHITE, "white");

    private static final Map<String, Side> SIDES = Map.ofEntries(
            Map.entry("red", new Side(NamedTextColor.RED, NamedTextColor.RED, "red")),
            Map.entry("blue", new Side(NamedTextColor.BLUE, NamedTextColor.BLUE, "blue")),
            Map.entry("green", new Side(NamedTextColor.GREEN, NamedTextColor.GREEN, "lime")),
            Map.entry("yellow", new Side(NamedTextColor.YELLOW, NamedTextColor.YELLOW, "yellow")),
            Map.entry("aqua", new Side(NamedTextColor.AQUA, NamedTextColor.AQUA, "cyan")),
            Map.entry("pink", new Side(NamedTextColor.LIGHT_PURPLE, NamedTextColor.LIGHT_PURPLE, "pink")),
            Map.entry("purple", new Side(NamedTextColor.DARK_PURPLE, TextColor.color(0x8B2FBE), "purple")),
            Map.entry("orange", new Side(NamedTextColor.GOLD, TextColor.color(0xE0791E), "orange")));

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
