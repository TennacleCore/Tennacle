package tennacle.games.bedwars;

import tennacle.games.shop.Price;
import tennacle.games.shop.Rank;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameType;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.codec.Codec;
import net.minestom.server.component.DataComponents;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.EnchantmentList;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.registry.RegistryKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static tennacle.games.bedwars.Resources.DIAMOND;

/**
 * The team upgrades (captured 2026-09-24 and 25): each a level on the team's ladder, bought with diamonds at the
 * upgrades keeper. Nothing here acts on a purchase: the kit's stamps and Haste, the forge and the Heal Pool read
 * the levels as they change.
 */
final class Upgrades {

    static final StateKey<Integer> SHARPNESS = StateKey.of("ledger.bedwars.sharpened-swords", Codec.INT, 0, true);
    static final StateKey<Integer> PROTECTION = StateKey.of("ledger.bedwars.reinforced-armor", Codec.INT, 0, true);
    static final StateKey<Integer> HASTE = StateKey.of("ledger.bedwars.maniac-miner", Codec.INT, 0, true);
    static final StateKey<Integer> HEAL_POOL = StateKey.of("ledger.bedwars.heal-pool", Codec.INT, 0, true);
    static final StateKey<Integer> CUSHIONED = StateKey.of("ledger.bedwars.cushioned-boots", Codec.INT, 0, true);

    /** One upgrade as the window shows it: its slot and icon, what it says, and each tier's name and effect. */
    record Upgrade(@NotNull Rank offer, int slot, @NotNull Material icon, @NotNull List<String> description,
                   @NotNull List<String> names, @NotNull List<String> tiers) {

        /** No tier lines: a cost line instead. */
        boolean single() {
            return tiers.isEmpty();
        }
    }

    static final Upgrade SHARPENED = new Upgrade(
            Rank.at("sharpened-swords", SHARPNESS, Rank.Holder.TEAM, 1, Price.of(4, DIAMOND)), 10, Material.IRON_SWORD,
            List.of("Your team permanently gains", "Sharpness I on all swords and axes!"), List.of("Sharpened Swords"), List.of());
    static final Upgrade REINFORCED = new Upgrade(
            Rank.next("reinforced-armor", PROTECTION, Rank.Holder.TEAM, prices(2, 4, 8, 16)), 11, Material.IRON_CHESTPLATE,
            List.of("Your team permanently gains", "Protection on all armor pieces!"),
            List.of("Reinforced Armor I", "Reinforced Armor II", "Reinforced Armor III", "Reinforced Armor IV"),
            List.of("Protection I", "Protection II", "Protection III", "Protection IV"));
    static final Upgrade MANIAC = new Upgrade(
            Rank.next("maniac-miner", HASTE, Rank.Holder.TEAM, prices(2, 4)), 12, Material.GOLDEN_PICKAXE,
            List.of("All players on your team", "permanently gain Haste."),
            List.of("Maniac Miner I", "Maniac Miner II"), List.of("Haste I", "Haste II"));
    static final Upgrade FORGE = new Upgrade(
            Rank.next("forge", Forges.LEVEL, Rank.Holder.TEAM, prices(2, 4, 6, 8)), 19, Material.FURNACE,
            List.of("Upgrade resource spawning and", "capacity on your island."),
            List.of("Iron Forge", "Golden Forge", "Emerald Forge", "Molten Forge"),
            List.of("+50% Resources", "+100% Resources", "Spawn emeralds", "+200% Resources"));
    static final Upgrade HEAL = new Upgrade(
            Rank.at("heal-pool", HEAL_POOL, Rank.Holder.TEAM, 1, Price.of(1, DIAMOND)), 20, Material.BEACON,
            List.of("Creates a Regeneration field around", "your base!"), List.of("Heal Pool"), List.of());
    static final Upgrade CUSHIONED_BOOTS = new Upgrade(
            Rank.next("cushioned-boots", CUSHIONED, Rank.Holder.TEAM, prices(1, 2)), 21, Material.DIAMOND_BOOTS,
            List.of("Your team permanently gains", "Feather Falling on your boots!"),
            List.of("Cushioned Boots I", "Cushioned Boots II"), List.of("Feather Falling I", "Feather Falling II"));

    static final List<Upgrade> ALL = List.of(SHARPENED, REINFORCED, MANIAC, FORGE, HEAL, CUSHIONED_BOOTS);

    private Upgrades() {}

    static @Nullable Upgrade at(int slot) {
        for (Upgrade upgrade : ALL) {
            if (upgrade.slot() == slot) return upgrade;
        }
        return null;
    }

    static int level(@NotNull Game game, @NotNull Slot seat, @NotNull StateKey<Integer> ladder) {
        return seat.team().isEmpty() ? 0 : game.state().get(Scope.team(seat.team()), ladder);
    }

    // captured: swords and axes, never pickaxes
    static ItemStack sharpened(Game game, Slot seat, ItemStack item) {
        String name = item.material().key().value();
        if (!name.endsWith("_sword") && !name.endsWith("_axe")) return item;
        return enchanted(item, Enchantment.SHARPNESS, level(game, seat, SHARPNESS));
    }

    static ItemStack reinforced(Game game, Slot seat, ItemStack item) {
        String name = item.material().key().value();
        if (!name.endsWith("_helmet") && !name.endsWith("_chestplate") && !name.endsWith("_leggings")
                && !name.endsWith("_boots")) return item;
        return enchanted(item, Enchantment.PROTECTION, level(game, seat, PROTECTION));
    }

    static ItemStack cushioned(Game game, Slot seat, ItemStack item) {
        if (!item.material().key().value().endsWith("_boots")) return item;
        return enchanted(item, Enchantment.FEATHER_FALLING, level(game, seat, CUSHIONED));
    }

    /** Haste's amplifier for the seat's team; none below zero. */
    static int haste(Game game, Slot seat) {
        return level(game, seat, HASTE) - 1;
    }

    // the team's level exactly, as the upgrade leaves it; none at zero
    private static ItemStack enchanted(ItemStack item, RegistryKey<Enchantment> enchantment, int level) {
        EnchantmentList list = item.get(DataComponents.ENCHANTMENTS, EnchantmentList.EMPTY);
        if (level > 0 ? list.level(enchantment) == level : !list.has(enchantment)) return item;
        return item.with(DataComponents.ENCHANTMENTS, level > 0 ? list.with(enchantment, level) : list.remove(enchantment));
    }

    // captured 3s/4s prices
    static void fourTeam(@NotNull GameType.Builder builder) {
        builder.rule(price(SHARPENED), Price.of(8, DIAMOND))
                .rule(prices(REINFORCED), prices(5, 10, 20, 30))
                .rule(prices(MANIAC), prices(4, 6))
                .rule(prices(FORGE), prices(4, 8, 12, 16))
                .rule(price(HEAL), Price.of(3, DIAMOND))
                .rule(prices(CUSHIONED_BOOTS), prices(2, 4));
    }

    private static StateKey<Price> price(Upgrade upgrade) {
        return upgrade.offer().priceRule();
    }

    private static StateKey<List<Price>> prices(Upgrade upgrade) {
        return upgrade.offer().pricesRule();
    }

    private static List<Price> prices(int... diamonds) {
        return java.util.Arrays.stream(diamonds).mapToObj(n -> Price.of(n, DIAMOND)).toList();
    }
}
