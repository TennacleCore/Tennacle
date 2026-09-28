package tennacle.games.bedwars;

import tennacle.games.shop.Goods;
import tennacle.games.shop.Offer;
import tennacle.games.shop.Price;
import tennacle.games.shop.Rank;
import tennacle.games.shop.Shop;

import io.github.term4.canvas.theme.Role;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameType;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.StateKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.EnchantmentList;
import net.minestom.server.item.component.PotionContents;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.potion.CustomPotionEffect;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.potion.PotionType;
import net.minestom.server.registry.RegistryKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static tennacle.games.bedwars.Resources.EMERALD;
import static tennacle.games.bedwars.Resources.GOLD;
import static tennacle.games.bedwars.Resources.IRON;

/**
 * The item shop's wares (captured 2026-09-24, Solo prices; the four-team ones from 2026-09-25): each offer with
 * the tab it sits in and how it reads there. Tools read by the tier they sell.
 */
final class Wares {

    /** The shop's tabs, left to right. */
    enum Tab {
        QUICK_BUY("Quick Buy", Material.NETHER_STAR),
        BLOCKS("Blocks", Material.TERRACOTTA),
        MELEE("Melee", Material.GOLDEN_SWORD),
        ARMOR("Armor", Material.CHAINMAIL_BOOTS),
        TOOLS("Tools", Material.STONE_PICKAXE),
        RANGED("Ranged", Material.BOW),
        POTIONS("Potions", Material.BREWING_STAND),
        UTILITY("Utility", Material.TNT),
        ROTATING("Rotating Items", Material.REDSTONE_TORCH);

        final String title;
        final Material icon;

        Tab(String title, Material icon) {
            this.title = title;
            this.icon = icon;
        }
    }

    /** How the shop shows one offer; a tool reads by the tier it sells, the rest by their first entry. */
    record Ware(@NotNull Offer offer, @NotNull Tab tab, @NotNull List<String> names, @NotNull List<ItemStack> icons,
                @NotNull List<Component> description, boolean permanent) {

        boolean upgradable() {
            return names.size() > 1;
        }

        /** The tier this viewer is shown, from 1. */
        int tier(Game game, Player viewer) {
            return offer instanceof Rank rank && upgradable() ? Math.max(1, rank.selling(game, viewer)) : 1;
        }

        String name(Game game, Player viewer) {
            return names.get(Math.min(tier(game, viewer), names.size()) - 1);
        }

        ItemStack icon(Game game, Player viewer) {
            return icons.get(Math.min(tier(game, viewer), icons.size()) - 1);
        }
    }

    static final Role<Role.Fill> WOOL = Role.block("blocks.wool");
    static final Role<Role.Fill> CLAY = Role.block("blocks.clay");
    static final Role<Role.Fill> GLASS = Role.block("blocks.glass");

    private static final Map<String, Ware> BY_ID = new LinkedHashMap<>();

    static {
        goods(Tab.BLOCKS, "wool", "Wool", teamBlock(WOOL, 16), ItemStack.of(Material.WHITE_WOOL, 16), Price.of(4, IRON),
                "blocks", "Great for bridging across islands.", "Turns into your team's color.");
        goods(Tab.BLOCKS, "clay", "Hardened Clay", teamBlock(CLAY, 16), ItemStack.of(Material.TERRACOTTA, 16),
                Price.of(12, IRON), "blocks", "Basic block to defend your bed.");
        goods(Tab.BLOCKS, "glass", "Blast-Proof Glass", teamBlock(GLASS, 4), ItemStack.of(Material.GLASS, 4),
                Price.of(12, IRON), "blocks", "Immune to explosions.");
        goods(Tab.BLOCKS, "endstone", "End Stone", Material.END_STONE, 12, Price.of(24, IRON), "blocks",
                "Solid block to defend your bed.");
        goods(Tab.BLOCKS, "ladder", "Ladder", Material.LADDER, 8, Price.of(4, IRON), "blocks",
                "Useful to save cats stuck in trees.");
        goods(Tab.BLOCKS, "wood", "Wood", Material.OAK_PLANKS, 16, Price.of(4, GOLD), "blocks",
                "Good block to defend your bed.", "Strong against pickaxes.");
        goods(Tab.BLOCKS, "obsidian", "Obsidian", Material.OBSIDIAN, 4, Price.of(4, EMERALD), "blocks",
                "Extreme protection for your bed.");
        goods(Tab.BLOCKS, "ice", "Packed Ice", Material.PACKED_ICE, 8, Price.of(8, IRON), "blocks",
                "Just normal ice. Good way to make a", "speedway.");
        sword("stone-sword", "Stone Sword", Material.STONE_SWORD, Price.of(10, IRON));
        sword("iron-sword", "Iron Sword", Material.IRON_SWORD, Price.of(7, GOLD));
        sword("diamond-sword", "Diamond Sword", Material.DIAMOND_SWORD, Price.of(4, EMERALD));
        ItemStack stick = enchanted(Material.STICK, Map.of(Enchantment.KNOCKBACK, 1));
        goods(Tab.MELEE, "stick", "Stick (Knockback I)", (g, p) -> stick, stick, Price.of(5, GOLD), "melee");
        armor("chainmail", "Permanent Chainmail Armor", Material.CHAINMAIL_BOOTS, 1, Price.of(24, IRON),
                "Chainmail leggings and boots which", "you will always spawn with.");
        armor("iron-armor", "Permanent Iron Armor", Material.IRON_BOOTS, 2, Price.of(12, GOLD),
                "Iron leggings and boots which you", "will always spawn with.");
        armor("diamond-armor", "Permanent Diamond Armor", Material.DIAMOND_BOOTS, 3, Price.of(6, EMERALD),
                "Diamond leggings and boots which", "you will always crush with."); // sic
        add(new Ware(Rank.at("shears", Gear.SHEARS, Rank.Holder.SEAT, 1, Price.of(20, IRON)), Tab.TOOLS,
                List.of("Permanent Shears"), List.of(ItemStack.of(Material.SHEARS)),
                gray("Great to get rid of wool. You will", "always spawn with these shears."), true));
        tool("pickaxe", Gear.PICKAXE, Gear.PICKAXES, List.of("Wooden Pickaxe (Efficiency I)", "Iron Pickaxe (Efficiency II)",
                "Golden Pickaxe (Efficiency III, Sharpness II)", "Diamond Pickaxe (Efficiency III)"));
        tool("axe", Gear.AXE, Gear.AXES, List.of("Wooden Axe (Efficiency I)", "Stone Axe (Efficiency I)",
                "Iron Axe (Efficiency II)", "Diamond Axe (Efficiency III)"));
        goods(Tab.RANGED, "arrow", "Arrow", Material.ARROW, 6, Price.of(2, GOLD), "ranged");
        goods(Tab.RANGED, "bow", "Bow", Material.BOW, 1, Price.of(12, GOLD), "ranged");
        ItemStack power = enchanted(Material.BOW, Map.of(Enchantment.POWER, 1));
        goods(Tab.RANGED, "power-bow", "Bow (Power I)", (g, p) -> power, power, Price.of(20, GOLD), "ranged");
        ItemStack punch = enchanted(Material.BOW, Map.of(Enchantment.POWER, 1, Enchantment.PUNCH, 1));
        goods(Tab.RANGED, "punch-bow", "Bow (Power I, Punch I)", (g, p) -> punch, punch, Price.of(6, EMERALD), "ranged");
        potion("speed", "Speed II Potion (45 seconds)", PotionType.SWIFTNESS, PotionEffect.SPEED, 1, 900,
                Price.of(1, EMERALD), "Speed II (0:45).");
        potion("jump", "Jump V Potion (45 seconds)", PotionType.LEAPING, PotionEffect.JUMP_BOOST, 4, 900,
                Price.of(1, EMERALD), "Jump Boost V (0:45).");
        potion("invisibility", "Invisibility Potion (30 seconds)", PotionType.INVISIBILITY, PotionEffect.INVISIBILITY,
                1, 600, Price.of(2, EMERALD), "Complete Invisibility (0:30).");
        goods(Tab.UTILITY, "golden-apple", "Golden Apple", Material.GOLDEN_APPLE, 1, Price.of(3, GOLD), "utility",
                "Well-rounded healing.");
        goods(Tab.UTILITY, "bedbug", "Bedbug", Material.SNOWBALL, 1, Price.of(24, IRON), "utility",
                "Spawns silverfish where the snowball", "lands to distract your enemies.", "Lasts 15 seconds.");
        goods(Tab.UTILITY, "dream-defender", "Dream Defender", Material.IRON_GOLEM_SPAWN_EGG, 1, Price.of(120, IRON),
                "utility", "Iron Golem to help defend your", "base. Lasts 4 minutes.");
        goods(Tab.UTILITY, "fireball", "Fireball", Material.FIRE_CHARGE, 1, Price.of(40, IRON), "utility",
                "Right-click to launch! Great to knock", "back enemies walking on thin bridges.");
        goods(Tab.UTILITY, "tnt", "TNT", Material.TNT, 1, Price.of(4, GOLD), "utility",
                "Instantly ignites, appropriate to", "explode things!");
        goods(Tab.UTILITY, "pearl", "Ender Pearl", Material.ENDER_PEARL, 1, Price.of(4, EMERALD), "utility",
                "The quickest way to invade enemy", "bases.");
        goods(Tab.UTILITY, "water", "Water Bucket", Material.WATER_BUCKET, 1, Price.of(2, GOLD), "utility",
                "Great to slow down approaching", "enemies. Can also protect against", "TNT.");
        goods(Tab.UTILITY, "bridge-egg", "Bridge Egg", Material.EGG, 1, Price.of(1, EMERALD), "utility",
                "This egg creates a bridge in its trail", "after being thrown.");
        goods(Tab.UTILITY, "magic-milk", "Magic Milk", Material.MILK_BUCKET, 1, Price.of(4, GOLD), "utility",
                "Avoid triggering traps for 30", "seconds after consuming.");
        goods(Tab.UTILITY, "sponge", "Sponge", Material.SPONGE, 4, Price.of(2, GOLD), "utility",
                "Great for soaking up water.");
        goods(Tab.UTILITY, "tower", "Compact Pop-up Tower", Material.TRAPPED_CHEST, 1, Price.of(24, IRON), "utility",
                "Place a pop-up defence!");
    }

    /** Quick Buy before a player changes it: all 21, the same in every mode (the wiki's mock-up). */
    static final List<String> QUICK_BUY = List.of(
            "wool", "stone-sword", "chainmail", "pickaxe", "bow", "speed", "tnt",
            "wood", "iron-sword", "iron-armor", "shears", "arrow", "invisibility", "water",
            "golden-apple", "jump", "glass", "endstone", "axe", "power-bow", "punch-bow");

    private Wares() {}

    static @Nullable Ware of(@NotNull String id) {
        return BY_ID.get(id);
    }

    static @NotNull List<Ware> in(@NotNull Tab tab) {
        return BY_ID.values().stream().filter(w -> w.tab() == tab).toList();
    }

    /** A shop selling every ware; one a game. */
    static @NotNull Shop shop() {
        Shop shop = Shop.of();
        BY_ID.values().forEach(w -> shop.offer(w.offer()));
        return shop;
    }

    // captured 4s, Holmgang 2026-09-25; the rest match Solo
    static void fourTeam(@NotNull GameType.Builder builder) {
        builder.rule(price("diamond-sword"), Price.of(3, EMERALD))
                .rule(price("tnt"), Price.of(8, GOLD))
                .rule(price("water"), Price.of(3, GOLD))
                .rule(price("sponge"), Price.of(3, GOLD));
    }

    private static StateKey<Price> price(String id) {
        Offer offer = BY_ID.get(id).offer();
        return offer instanceof Sword sword ? sword.rule() : ((Goods) offer).rule();
    }

    private static void add(Ware ware) {
        if (BY_ID.putIfAbsent(ware.offer().id(), ware) != null) throw new IllegalStateException("two wares are " + ware.offer().id());
    }

    private static void goods(Tab tab, String id, String name, Material material, int count, Price price, String role,
                              String... description) {
        ItemStack stack = ItemStack.of(material, count);
        goods(tab, id, name, (g, p) -> stack, stack, price, role, description);
    }

    private static void goods(Tab tab, String id, String name, BiFunction<Game, Player, ItemStack> stack, ItemStack icon,
                              Price price, String role, String... description) {
        add(new Ware(Goods.of(id, price, role, stack), tab, List.of(name), List.of(icon), gray(description), false));
    }

    private static void sword(String id, String name, Material material, Price price) {
        add(new Ware(new Sword(id, material, price), Tab.MELEE, List.of(name), List.of(ItemStack.of(material)), List.of(), false));
    }

    private static void armor(String id, String name, Material icon, int tier, Price price, String... description) {
        add(new Ware(Rank.at(id, Gear.ARMOR, Rank.Holder.SEAT, tier, price), Tab.ARMOR, List.of(name),
                List.of(ItemStack.of(icon)), gray(description), true));
    }

    private static void tool(String id, StateKey<Integer> ladder, List<ItemStack> tiers,
                             List<String> names) {
        Rank rank = Rank.next(id, ladder, Rank.Holder.SEAT,
                List.of(Price.of(10, IRON), Price.of(10, IRON), Price.of(3, GOLD), Price.of(6, GOLD)));
        add(new Ware(rank, Tab.TOOLS, names, tiers, List.of(), true));
    }

    private static void potion(String id, String name, PotionType type, PotionEffect effect, int amplifier, int ticks,
                               Price price, String line) {
        ItemStack stack = ItemStack.of(Material.POTION).with(DataComponents.POTION_CONTENTS, new PotionContents(type, null,
                List.of(new CustomPotionEffect(effect, amplifier, ticks, false, true, true))));
        add(new Ware(Goods.of(id, price, "potions", (g, p) -> stack), Tab.POTIONS, List.of(name), List.of(stack),
                List.of(Component.text(line, NamedTextColor.BLUE)), false));
    }

    private static ItemStack enchanted(Material material, Map<RegistryKey<Enchantment>, Integer> enchants) {
        return ItemStack.of(material).with(DataComponents.ENCHANTMENTS, new EnchantmentList(enchants));
    }

    // the look's own family for a buyer with no team
    private static BiFunction<Game, Player, ItemStack> teamBlock(Role<Role.Fill> role, int count) {
        return (game, buyer) -> {
            Slot seat = game.roster().slotOf(buyer);
            Team team = seat != null ? game.roster().team(seat.team()) : null;
            Material block = (team != null ? team.livery() : BedWars.LOOK).get(role).material();
            return ItemStack.of(block, count);
        };
    }

    private static List<Component> gray(String... lines) {
        List<Component> out = new ArrayList<>();
        Arrays.stream(lines).forEach(line -> out.add(Component.text(line, NamedTextColor.GRAY)));
        return List.copyOf(out);
    }
}
