package tennacle.games.bedwars;

import tennacle.games.bedwars.HotbarOrders.Layouts;
import tennacle.games.imports.HypixelLayouts;
import tennacle.games.imports.KitImports;
import tennacle.games.imports.LayoutImport;
import tennacle.games.shop.Favorites;
import tennacle.hypixel.HypixelApi;

import net.minestom.server.item.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * A player's Bed Wars quick buy ({@code favourites_2}, 21 shop ids) and hotbar ({@code favorite_slots}, a category or
 * {@code null} a slot) from Hypixel, each its own part. Their after-bed layout is ours alone and stays.
 */
final class HypixelLayout {

    static final String MODE = "bedwars", TITLE = "Bed Wars";

    private HypixelLayout() {}

    static void install() {
        KitImports.add(new LayoutImport(HypixelLayouts.SOURCE, MODE, TITLE, "quickbuy", "Quick Buy", Material.NETHER_STAR, profile -> {
            String csv = HypixelApi.string(HypixelApi.object(profile, "stats", "Bedwars"), "favourites_2");
            if (csv == null) return null;
            List<String> pins = quickBuy(csv);
            return p -> Favorites.set(p, ItemShop.FRONT, pins);
        }));
        KitImports.add(new LayoutImport(HypixelLayouts.SOURCE, MODE, TITLE, "hotbar", "Hotbar", Material.BLAZE_POWDER, profile -> {
            String csv = HypixelApi.string(HypixelApi.object(profile, "stats", "Bedwars"), "favorite_slots");
            if (csv == null) return null;
            List<List<String>> hotbar = hotbar(csv);
            return p -> HotbarOrders.set(p, new Layouts(hotbar, HotbarOrders.of(p).afterBed()));
        }));
    }

    // an item we don't sell (a rotating one) leaves its pin empty
    static List<String> quickBuy(String csv) {
        String[] ids = csv.split(",");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < ItemShop.GRID.length; i++) {
            String ware = i < ids.length ? Wares.fromHypixel(ids[i].trim()) : null;
            out.add(ware != null ? ware : "");
        }
        return out;
    }

    static List<List<String>> hotbar(String csv) {
        String[] kinds = csv.split(",");
        List<List<String>> out = new ArrayList<>(HotbarOrders.empty());
        for (int slot = 0; slot < Math.min(kinds.length, HotbarOrders.SLOTS); slot++) {
            for (HotbarManager.Category category : HotbarManager.CATEGORIES) {
                if (category.name().equalsIgnoreCase(kinds[slot].trim())) out.set(slot, List.of(category.role()));
            }
        }
        return out;
    }
}
