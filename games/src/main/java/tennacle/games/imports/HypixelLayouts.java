package tennacle.games.imports;

import tennacle.hypixel.HypixelApi;

import io.github.term4.grotto.kit.Kit;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/** Hypixel as a source: its public API, read with the server's key. */
public final class HypixelLayouts {

    public static final String SOURCE = "hypixel";

    private HypixelLayouts() {}

    public static void install() {
        KitImports.source(new ImportSource(SOURCE, "Hypixel", Material.GOLD_BLOCK, HypixelApi::enabled, HypixelApi::player));
    }

    /** A kit from one of Hypixel's Duels layouts ({@code layout_bridge_duel_layout}), slot to Hypixel item id. */
    public static void duel(@NotNull String title, @NotNull Kit kit, @NotNull String layout, @NotNull Map<String, String> names) {
        KitImports.kit(SOURCE, title, kit, profile -> HypixelApi.object(profile, "stats", "Duels", layout), names);
    }
}
