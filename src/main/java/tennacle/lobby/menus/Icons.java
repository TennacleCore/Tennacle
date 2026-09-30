package tennacle.lobby.menus;

import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import tennacle.games.Mode;

/** The item a mode shows as in menus. */
public final class Icons {

    private Icons() {}

    public static @NotNull Material of(@NotNull Mode mode) {
        return of(mode.name());
    }

    /** The icon of a mode known only by its title ({@code Bed Wars Doubles}). */
    public static @NotNull Material titled(@NotNull String title) {
        String lower = title.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("bed wars")) return Material.RED_BED;
        if (lower.startsWith("bed fight")) return Material.WHITE_BED;
        if (lower.startsWith("bridge")) return Material.BLUE_TERRACOTTA;
        if (lower.startsWith("sumo")) return Material.SLIME_BALL;
        if (lower.startsWith("boxing")) return Material.DIAMOND_SWORD;
        return Material.PAPER;
    }

    public static @NotNull Material of(@NotNull String mode) {
        if (mode.startsWith("bedwars")) return Material.RED_BED;
        return switch (mode) {
            case "bridge" -> Material.BLUE_TERRACOTTA;
            case "sumo" -> Material.SLIME_BALL;
            case "boxing" -> Material.DIAMOND_SWORD;
            case "bedfight" -> Material.WHITE_BED;
            default -> Material.PAPER;
        };
    }
}
