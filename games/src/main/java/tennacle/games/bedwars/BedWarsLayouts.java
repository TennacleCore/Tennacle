package tennacle.games.bedwars;

import tennacle.games.bedwars.HotbarOrders.Layouts;
import tennacle.games.imports.HypixelLayouts;
import tennacle.games.imports.KitImports;
import tennacle.games.imports.LayoutImport;
import tennacle.games.imports.LayoutPart;
import tennacle.games.shop.Favorites;
import tennacle.hypixel.HypixelApi;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minestom.server.item.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * Bed Wars' two shareable parts. Quick buy is a JSON array of 21 ware ids, {@code ""} for an open pin. The hotbar is
 * {@code {"main": [[pick...] x9], "after": [...]}}; one without {@code after} leaves the player's own after-bed layout.
 * Hypixel keeps both: {@code favourites_2}, 21 shop ids, and {@code favorite_slots}, a category or {@code null} a slot.
 */
final class BedWarsLayouts {

    static final String MODE = "bedwars", TITLE = "Bed Wars";

    private BedWarsLayouts() {}

    static void install() {
        KitImports.part(new LayoutPart(MODE, TITLE, "quickbuy", "Quick Buy", Material.NETHER_STAR,
                owner -> ItemShop.loadInUse(owner).thenApply(pins -> pins != null ? strings(pins) : null),
                (player, value) -> {
                    List<String> pins = new ArrayList<>();
                    value.getAsJsonArray().forEach(id -> pins.add(id.getAsString()));
                    while (pins.size() < ItemShop.GRID.length) pins.add("");
                    Favorites.set(player, ItemShop.front(ItemShop.inUse(player)), pins.subList(0, ItemShop.GRID.length));
                }));
        KitImports.part(new LayoutPart(MODE, TITLE, "hotbar", "Hotbar", Material.BLAZE_POWDER,
                owner -> HotbarOrders.load(owner).thenApply(held -> {
                    if (held == null) return null;
                    JsonObject out = new JsonObject();
                    out.add("main", slots(held.main()));
                    out.add("after", slots(held.afterBed()));
                    return out;
                }),
                (player, value) -> {
                    JsonObject held = value.getAsJsonObject();
                    List<List<String>> after = held.has("after") ? slots(held.getAsJsonArray("after")) : HotbarOrders.of(player).afterBed();
                    HotbarOrders.set(player, new Layouts(slots(held.getAsJsonArray("main")), after));
                }));
        KitImports.add(new LayoutImport(HypixelLayouts.SOURCE, MODE, "quickbuy", profile -> {
            String csv = HypixelApi.string(HypixelApi.object(profile, "stats", "Bedwars"), "favourites_2");
            return csv != null ? strings(quickBuy(csv)) : null;
        }));
        KitImports.add(new LayoutImport(HypixelLayouts.SOURCE, MODE, "hotbar", profile -> {
            String csv = HypixelApi.string(HypixelApi.object(profile, "stats", "Bedwars"), "favorite_slots");
            if (csv == null) return null;
            JsonObject out = new JsonObject();
            out.add("main", slots(hotbar(csv)));
            return out;
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

    private static JsonArray strings(List<String> values) {
        JsonArray out = new JsonArray();
        values.forEach(out::add);
        return out;
    }

    private static JsonArray slots(List<List<String>> layout) {
        JsonArray out = new JsonArray();
        layout.forEach(slot -> out.add(strings(slot)));
        return out;
    }

    private static List<List<String>> slots(JsonArray layout) {
        List<List<String>> out = new ArrayList<>();
        for (JsonElement slot : layout) {
            List<String> picks = new ArrayList<>();
            slot.getAsJsonArray().forEach(pick -> picks.add(pick.getAsString()));
            out.add(List.copyOf(picks));
        }
        return out;
    }
}
