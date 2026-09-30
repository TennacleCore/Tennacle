package tennacle.dev;

import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.PotionContents;
import net.minestom.server.potion.CustomPotionEffect;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.potion.PotionType;

/** The mechanics test rig's body: survival, diamond, one of everything the systems handle. {@code /arsenal}. */
public final class Arsenal {

    private Arsenal() {}

    public static void give(Player p) {
        p.setGameMode(GameMode.SURVIVAL);
        p.getInventory().clear();
        p.setHelmet(ItemStack.of(Material.DIAMOND_HELMET));
        p.setChestplate(ItemStack.of(Material.DIAMOND_CHESTPLATE));
        p.setLeggings(ItemStack.of(Material.DIAMOND_LEGGINGS));
        p.setBoots(ItemStack.of(Material.DIAMOND_BOOTS));
        var inv = p.getInventory();
        inv.addItemStack(ItemStack.of(Material.STONE, 64));
        inv.addItemStack(ItemStack.of(Material.RED_WOOL, 64));
        inv.addItemStack(ItemStack.of(Material.LADDER, 64));
        inv.addItemStack(ItemStack.of(Material.IRON_PICKAXE));
        inv.addItemStack(ItemStack.of(Material.DIAMOND_SWORD));
        inv.addItemStack(ItemStack.of(Material.BOW));
        inv.addItemStack(ItemStack.of(Material.ARROW, 64));
        inv.addItemStack(ItemStack.of(Material.SNOWBALL, 64));
        inv.addItemStack(ItemStack.of(Material.EGG, 16));
        inv.addItemStack(ItemStack.of(Material.ENDER_PEARL, 16));
        inv.addItemStack(ItemStack.of(Material.FISHING_ROD));
        inv.addItemStack(ItemStack.of(Material.FIRE_CHARGE, 64)); // right-click to throw a fireball
        inv.addItemStack(ItemStack.of(Material.TNT, 64)); // place to spawn a primed TNT
        inv.addItemStack(ItemStack.of(Material.GOLDEN_APPLE, 16));
        inv.addItemStack(ItemStack.of(Material.ENCHANTED_GOLDEN_APPLE, 16));
        inv.addItemStack(ItemStack.of(Material.MILK_BUCKET));
        // a drinkable Speed potion (custom effect, so it works without extending VanillaPotions)
        inv.addItemStack(ItemStack.of(Material.POTION).with(DataComponents.POTION_CONTENTS,
                new PotionContents(new CustomPotionEffect(PotionEffect.SPEED, 0, 1200, false, true, true))));
        // registry-id potions (amount 1: potions are unstackable, an overstacked addItemStack silently no-ops)
        inv.addItemStack(ItemStack.of(Material.POTION).with(DataComponents.POTION_CONTENTS,
                new PotionContents(PotionType.HEALING)));
        inv.addItemStack(ItemStack.of(Material.SPLASH_POTION).with(DataComponents.POTION_CONTENTS,
                new PotionContents(PotionType.SWIFTNESS)));
        inv.addItemStack(ItemStack.of(Material.SPLASH_POTION).with(DataComponents.POTION_CONTENTS,
                new PotionContents(PotionType.HARMING)));
    }
}
