package tennacle.games.bedwars;

import tennacle.games.HypixelImports;
import tennacle.games.bedwars.HotbarOrders.Layouts;
import tennacle.games.shop.Favorites;
import tennacle.hypixel.HypixelApi;

import com.google.gson.JsonObject;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A player's Bed Wars quick buy ({@code favourites_2}, 21 shop ids) and hotbar ({@code favorite_slots}, a category or
 * {@code null} a slot) from Hypixel. Their after-bed layout is ours alone and stays.
 */
final class HypixelLayout {

    static final HypixelImports.Import IMPORT = new HypixelImports.Import("bedwars", "Bed Wars", HypixelLayout::read);

    private HypixelLayout() {}

    static @Nullable Consumer<Player> read(@NotNull JsonObject player) {
        JsonObject bedwars = HypixelApi.object(player, "stats", "Bedwars");
        String quick = HypixelApi.string(bedwars, "favourites_2");
        String slots = HypixelApi.string(bedwars, "favorite_slots");
        List<String> pins = quick != null ? quickBuy(quick) : null;
        List<List<String>> hotbar = slots != null ? hotbar(slots) : null;
        if (pins == null && hotbar == null) return null;
        return p -> {
            if (pins != null) Favorites.set(p, ItemShop.FRONT, pins);
            if (hotbar != null) HotbarOrders.set(p, new Layouts(hotbar, HotbarOrders.of(p).afterBed()));
        };
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
