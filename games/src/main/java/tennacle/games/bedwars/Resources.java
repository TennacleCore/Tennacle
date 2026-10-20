package tennacle.games.bedwars;

import tennacle.games.shop.Currency;
import tennacle.games.shop.ItemCurrency;

import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.item.Material;

/** The four resources, spent as the items the generators drop; captured names and price colors. */
final class Resources {

    static final ItemCurrency IRON = Currency.register(
            new ItemCurrency("iron", Material.IRON_INGOT, "Iron", "Iron", NamedTextColor.WHITE));
    static final ItemCurrency GOLD = Currency.register(
            new ItemCurrency("gold", Material.GOLD_INGOT, "Gold", "Gold", NamedTextColor.GOLD));
    static final ItemCurrency DIAMOND = Currency.register(
            new ItemCurrency("diamond", Material.DIAMOND, "Diamond", "Diamonds", NamedTextColor.AQUA));
    static final ItemCurrency EMERALD = Currency.register(
            new ItemCurrency("emerald", Material.EMERALD, "Emerald", "Emeralds", NamedTextColor.DARK_GREEN));

    /** Each one's item, by its name in the stats. */
    static final java.util.Map<Material, String> NAMES = java.util.Map.of(IRON.material(), IRON.id(), GOLD.material(), GOLD.id(),
            DIAMOND.material(), DIAMOND.id(), EMERALD.material(), EMERALD.id());

    private Resources() {}
}
