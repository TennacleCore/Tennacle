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

    public static @NotNull Material of(@NotNull String mode) {
        if (mode.startsWith("bedwars")) return Material.RED_BED;
        return switch (mode) {
            case "bridge" -> Material.BLUE_TERRACOTTA;
            case "sumo" -> Material.SLIME_BALL;
            case "boxing" -> Material.DIAMOND_SWORD;
            case "practice" -> Material.ARMOR_STAND;
            case "bedfight" -> Material.WHITE_BED;
            default -> Material.PAPER;
        };
    }
}
