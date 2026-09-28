package tennacle.games.bedwars;

import tennacle.games.beds.Lives;

import io.github.term4.grotto.Game;
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
import net.minestom.server.utils.Unit;

import java.util.List;
import java.util.Map;

/**
 * What a seat has bought for good, as ladders the kit reads: the armor tier (legs and boots), the pickaxe, the
 * axe and the shears. A death drops each tool a tier, tier I staying for good; armor and shears stay.
 */
final class Gear {

    /** Leggings and boots: 0 leather, 1 chainmail, 2 iron, 3 diamond. */
    static final StateKey<Integer> ARMOR = StateKey.of("ledger.bedwars.armor", Codec.INT, 0, true);
    /** 0 none, then wooden, iron, golden, diamond. */
    static final StateKey<Integer> PICKAXE = StateKey.of("ledger.bedwars.pickaxe", Codec.INT, 0, true);
    /** 0 none, then wooden, stone, iron, diamond. */
    static final StateKey<Integer> AXE = StateKey.of("ledger.bedwars.axe", Codec.INT, 0, true);
    static final StateKey<Integer> SHEARS = StateKey.of("ledger.bedwars.shears", Codec.INT, 0, true);

    private static final List<Material> LEGS = List.of(Material.LEATHER_LEGGINGS, Material.CHAINMAIL_LEGGINGS,
            Material.IRON_LEGGINGS, Material.DIAMOND_LEGGINGS);
    private static final List<Material> BOOTS = List.of(Material.LEATHER_BOOTS, Material.CHAINMAIL_BOOTS,
            Material.IRON_BOOTS, Material.DIAMOND_BOOTS);

    // captured
    static final List<ItemStack> PICKAXES = List.of(
            tool(Material.WOODEN_PICKAXE, Map.of(Enchantment.EFFICIENCY, 1)),
            tool(Material.IRON_PICKAXE, Map.of(Enchantment.EFFICIENCY, 2)),
            tool(Material.GOLDEN_PICKAXE, Map.of(Enchantment.EFFICIENCY, 3, Enchantment.SHARPNESS, 2)),
            tool(Material.DIAMOND_PICKAXE, Map.of(Enchantment.EFFICIENCY, 3)));
    static final List<ItemStack> AXES = List.of(
            tool(Material.WOODEN_AXE, Map.of(Enchantment.EFFICIENCY, 1)),
            tool(Material.STONE_AXE, Map.of(Enchantment.EFFICIENCY, 1)),
            tool(Material.IRON_AXE, Map.of(Enchantment.EFFICIENCY, 2)),
            tool(Material.DIAMOND_AXE, Map.of(Enchantment.EFFICIENCY, 3)));
    static final ItemStack SHEARS_ITEM = ItemStack.of(Material.SHEARS);

    private Gear() {}

    private static ItemStack tool(Material material, Map<RegistryKey<Enchantment>, Integer> enchants) {
        return ItemStack.of(material).with(DataComponents.ENCHANTMENTS, new EnchantmentList(enchants));
    }

    static int level(Game game, Slot seat, StateKey<Integer> ladder) {
        return game.state().get(Scope.slot(seat.index()), ladder);
    }

    static Material legs(Game game, Slot seat) {
        return LEGS.get(Math.min(level(game, seat, ARMOR), LEGS.size() - 1));
    }

    static Material boots(Game game, Slot seat) {
        return BOOTS.get(Math.min(level(game, seat, ARMOR), BOOTS.size() - 1));
    }

    static ItemStack pickaxe(Game game, Slot seat) {
        return tier(PICKAXES, level(game, seat, PICKAXE));
    }

    static ItemStack axe(Game game, Slot seat) {
        return tier(AXES, level(game, seat, AXE));
    }

    static ItemStack shears(Game game, Slot seat) {
        return level(game, seat, SHEARS) > 0 ? SHEARS_ITEM : ItemStack.AIR;
    }

    private static ItemStack tier(List<ItemStack> tiers, int level) {
        return level <= 0 ? ItemStack.AIR : tiers.get(Math.min(level, tiers.size()) - 1);
    }

    /** A bought sword, the kind the wooden one gives way to. */
    static boolean sword(ItemStack item) {
        return item.material() == Material.STONE_SWORD || item.material() == Material.IRON_SWORD
                || item.material() == Material.DIAMOND_SWORD;
    }

    /** The wooden sword and the tools: the seat's for good, so never stored. */
    static boolean kept(ItemStack item) {
        Material material = item.material();
        String name = material.key().value();
        return material == Material.WOODEN_SWORD || material == Material.SHEARS
                || name.endsWith("_pickaxe") || name.endsWith("_axe");
    }

    /** Nothing a seat wears or swings wears out. */
    static ItemStack unbreakable(Game game, Slot seat, ItemStack item) {
        return item.has(DataComponents.MAX_DAMAGE) && !item.has(DataComponents.UNBREAKABLE)
                ? item.with(DataComponents.UNBREAKABLE, Unit.INSTANCE) : item;
    }

    static void died(Lives.Death death) {
        if (death.last()) return;
        Game game = death.game();
        Scope seat = Scope.slot(death.seat().index());
        for (StateKey<Integer> tool : List.of(PICKAXE, AXE)) {
            int level = game.state().get(seat, tool);
            if (level > 1) game.state().set(seat, tool, level - 1);
        }
    }
}
