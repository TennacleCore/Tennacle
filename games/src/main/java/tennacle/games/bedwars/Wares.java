package tennacle.games.bedwars;

import io.github.term4.canvas.screen.Items;
import tennacle.games.Skins;
import tennacle.games.shop.Goods;
import tennacle.games.shop.Offer;
import tennacle.games.shop.Price;
import tennacle.games.shop.Rank;
import tennacle.games.shop.Shop;

import io.github.term4.canvas.theme.Role;
import io.github.term4.polyp.item.ItemKind;
import io.github.term4.polyp.presets.hypixel.Projectiles;
import io.github.term4.polyp.presets.hypixel.Tnt;
import tennacle.games.TeamBlocks;
import io.github.term4.canvas.theme.Tint;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameType;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.StateKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.instance.block.Block;
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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    record Ware(@NotNull Offer offer, @NotNull Tab tab, @NotNull List<String> names,
                @NotNull List<BiFunction<Game, Player, ItemStack>> icons, @NotNull List<Component> description,
                boolean permanent) {

        static List<BiFunction<Game, Player, ItemStack>> fixed(List<ItemStack> icons) {
            return icons.stream().<BiFunction<Game, Player, ItemStack>>map(icon -> (g, p) -> icon).toList();
        }

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
            return icons.get(Math.min(tier(game, viewer), icons.size()) - 1).apply(game, viewer);
        }
    }

    static final Role<Role.Fill> WOOL = TeamBlocks.WOOL, CLAY = TeamBlocks.CLAY, GLASS = TeamBlocks.GLASS;

    private static final Map<String, Ware> BY_ID = new LinkedHashMap<>();
    private static final Map<Material, String> ROLES = new HashMap<>();
    /** A ware an item can be picked as: its material once the team color and skin are off, its shop icon to tell like ones apart. */
    // a tool's every tier is the one pick
    private record Pick(String id, List<Material> plains, @Nullable ItemStack shown) {}
    private static final Map<String, Pick> PICKS = new LinkedHashMap<>();

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
        BiFunction<Game, Player, ItemStack> wood = (g, p) -> Skins.worn(p, ItemStack.of(Material.OAK_PLANKS, 16));
        goods(Tab.BLOCKS, "wood", "Wood", wood, wood, Material.OAK_PLANKS, Price.of(4, GOLD), "blocks",
                "Good block to defend your bed.", "Strong against pickaxes.");
        goods(Tab.BLOCKS, "obsidian", "Obsidian", Material.OBSIDIAN, 4, Price.of(4, EMERALD), "blocks",
                "Extreme protection for your bed.");
        goods(Tab.BLOCKS, "ice", "Packed Ice", Material.PACKED_ICE, 8, Price.of(8, IRON), "blocks",
                "Just normal ice. Good way to make a", "speedway.");
        goods(Tab.MELEE, "stone-sword", "Stone Sword", Material.STONE_SWORD, 1, Price.of(10, IRON), "melee");
        goods(Tab.MELEE, "iron-sword", "Iron Sword", Material.IRON_SWORD, 1, Price.of(7, GOLD), "melee");
        goods(Tab.MELEE, "diamond-sword", "Diamond Sword", Material.DIAMOND_SWORD, 1, Price.of(4, EMERALD), "melee");
        ItemStack stick = enchanted(Material.STICK, Map.of(Enchantment.KNOCKBACK, 1));
        goods(Tab.MELEE, "stick", "Stick (Knockback I)", (g, p) -> stick, stick, Price.of(5, GOLD), "melee");
        armor("chainmail", "Permanent Chainmail Armor", Material.CHAINMAIL_BOOTS, 1, Price.of(24, IRON),
                "Chainmail leggings and boots which", "you will always spawn with.");
        armor("iron-armor", "Permanent Iron Armor", Material.IRON_BOOTS, 2, Price.of(12, GOLD),
                "Iron leggings and boots which you", "will always spawn with.");
        armor("diamond-armor", "Permanent Diamond Armor", Material.DIAMOND_BOOTS, 3, Price.of(6, EMERALD),
                "Diamond leggings and boots which", "you will always crush with."); // sic
        add(new Ware(Rank.at("shears", Gear.SHEARS, Rank.Holder.SEAT, 1, Price.of(20, IRON)), Tab.TOOLS,
                List.of("Permanent Shears"), Ware.fixed(List.of(ItemStack.of(Material.SHEARS))),
                Items.gray("Great to get rid of wool. You will", "always spawn with these shears."), true));
        PICKS.put("shears", new Pick("shears", List.of(Material.SHEARS), null));
        tool("pickaxe", Gear.PICKAXE, Gear.PICKAXES, List.of("Wooden Pickaxe (Efficiency I)", "Iron Pickaxe (Efficiency II)",
                "Golden Pickaxe (Efficiency III, Sharpness II)", "Diamond Pickaxe (Efficiency III)"));
        tool("axe", Gear.AXE, Gear.AXES, List.of("Wooden Axe (Efficiency I)", "Stone Axe (Efficiency I)",
                "Iron Axe (Efficiency II)", "Diamond Axe (Efficiency III)"));
        goods(Tab.RANGED, "arrow", "Arrow", Material.ARROW, 6, Price.of(2, GOLD), "ammo");
        goods(Tab.RANGED, "bow", "Bow", Material.BOW, 1, Price.of(12, GOLD), "ranged");
        ItemStack power = enchanted(Material.BOW, Map.of(Enchantment.POWER, 1));
        goods(Tab.RANGED, "power-bow", "Bow (Power I)", (g, p) -> power, power, Price.of(20, GOLD), "ranged");
        ItemStack punch = enchanted(Material.BOW, Map.of(Enchantment.POWER, 1, Enchantment.PUNCH, 1));
        goods(Tab.RANGED, "punch-bow", "Bow (Power I, Punch I)", (g, p) -> punch, punch, Price.of(6, EMERALD), "ranged");
        // captured 373:8195, the fire resistance potion's 1.8 value; its custom effect stands in for fire resistance
        potion("speed", "Speed II Potion (45 seconds)", PotionType.FIRE_RESISTANCE, PotionEffect.SPEED, 1, 900, true,
                Price.of(1, EMERALD), "Speed II (0:45).");
        potion("jump", "Jump V Potion (45 seconds)", PotionType.LEAPING, PotionEffect.JUMP_BOOST, 4, 900, true,
                Price.of(1, EMERALD), "Jump Boost V (0:45).");
        potion("invisibility", "Invisibility Potion (30 seconds)", PotionType.INVISIBILITY, PotionEffect.INVISIBILITY,
                1, 600, false, Price.of(2, EMERALD), "Complete Invisibility (0:30)."); // the user (2026-09-28): no swirl to give the drinker away
        goods(Tab.UTILITY, "golden-apple", "Golden Apple", Material.GOLDEN_APPLE, 1, Price.of(3, GOLD), "utility",
                "Well-rounded healing.");
        goods(Tab.UTILITY, "bedbug", "Bedbug", Material.SNOWBALL, 1, Price.of(24, IRON), "utility",
                "Spawns silverfish where the snowball", "lands to distract your enemies.", "Lasts 15 seconds.");
        goods(Tab.UTILITY, "dream-defender", "Dream Defender", Material.IRON_GOLEM_SPAWN_EGG, 1, Price.of(120, IRON),
                "utility", "Iron Golem to help defend your", "base. Lasts 4 minutes.");
        goods(Tab.UTILITY, "fireball", "Fireball", ItemKind.stamp(ItemStack.of(Material.FIRE_CHARGE), Projectiles.FIREBALL), Price.of(40, IRON), "utility",
                "Right-click to launch! Great to knock", "back enemies walking on thin bridges.");
        goods(Tab.UTILITY, "tnt", "TNT", ItemKind.stamp(ItemStack.of(Material.TNT), Tnt.KIND), Price.of(4, GOLD), "utility",
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

    /** The ware Hypixel's quick buy calls {@code id}: a permanent one by its first item, the rest by shop name. */
    static @Nullable String fromHypixel(@NotNull String id) {
        for (Ware ware : BY_ID.values()) {
            String key = ware.permanent() ? ware.icons().getFirst().apply(null, null).material().key().value()
                    : ware.names().getFirst().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
            if (key.equals(id)) return ware.offer().id();
        }
        return null;
    }

    static @Nullable Ware of(@NotNull String id) {
        return BY_ID.get(id);
    }

    /** Every ware id, in catalog order. */
    static @NotNull java.util.Collection<String> ids() {
        return java.util.Collections.unmodifiableSet(BY_ID.keySet());
    }

    static @NotNull List<Ware> in(@NotNull Tab tab) {
        return BY_ID.values().stream().filter(w -> w.tab() == tab).toList();
    }

    /** The Hotbar Manager category an item plays, by the tab and kind it is sold as; null for none. */
    static @Nullable String role(@NotNull ItemStack item) {
        String name = item.material().key().value();
        if (name.endsWith("_sword")) return "melee";
        if (name.endsWith("_pickaxe")) return "pickaxe";
        if (name.endsWith("_axe")) return "axe";
        String family = Tint.family(name);
        if ("wool".equals(family) || "terracotta".equals(family) || "stained_glass".equals(family)) return "blocks";
        Skins.Family skinned = Skins.familyOf(item.material());
        return ROLES.get(skinned != null ? skinned.plain() : item.material());
    }

    /** The ware {@code item} is, for a hotbar order: what the shop sells it as, team color, skin and upgrades aside. */
    static @Nullable String pickOf(@NotNull ItemStack item) {
        Material plain = plainOf(item.material());
        Pick first = null;
        for (Pick pick : PICKS.values()) {
            if (!pick.plains().contains(plain)) continue;
            if (pick.shown() != null && Objects.equals(pick.shown().get(DataComponents.ENCHANTMENTS), item.get(DataComponents.ENCHANTMENTS))
                    && Objects.equals(pick.shown().get(DataComponents.POTION_CONTENTS), item.get(DataComponents.POTION_CONTENTS))) {
                return pick.id();
            }
            if (first == null) first = pick;
        }
        return first != null ? first.id() : null;
    }

    /** {@link #pickOf} as the kit's picks. */
    static @NotNull List<String> picks(@NotNull ItemStack item) {
        String id = pickOf(item);
        return id != null ? List.of(HotbarOrders.WARE + id) : List.of();
    }

    /** The ware's shop icon for {@code viewer}; with no game, the plain one, as the shop shows white wool. */
    static @NotNull ItemStack icon(@NotNull Ware ware, @Nullable Game game, @NotNull Player viewer) {
        if (game != null) return ware.icon(game, viewer);
        Pick pick = PICKS.get(ware.offer().id());
        if (pick == null) return ware.icons().getFirst().apply(null, viewer);
        return pick.shown() != null ? pick.shown() : ItemStack.of(pick.plains().getFirst());
    }

    /** Every ware a hotbar order can name, in shop order. */
    static @NotNull List<Ware> pickable() {
        return PICKS.keySet().stream().map(BY_ID::get).filter(Objects::nonNull).toList();
    }

    private static Material plainOf(Material material) {
        String family = Tint.family(material.key().value());
        if ("wool".equals(family)) return Material.WHITE_WOOL;
        if ("terracotta".equals(family)) return Material.TERRACOTTA;
        if ("stained_glass".equals(family)) return Material.GLASS;
        Skins.Family skinned = Skins.familyOf(material);
        return skinned != null ? skinned.plain() : material;
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
        return ((Goods) BY_ID.get(id).offer()).rule();
    }

    private static void add(Ware ware) {
        if (BY_ID.putIfAbsent(ware.offer().id(), ware) != null) throw new IllegalStateException("two wares are " + ware.offer().id());
    }

    private static void goods(Tab tab, String id, String name, Material material, int count, Price price, String role,
                              String... description) {
        goods(tab, id, name, ItemStack.of(material, count), price, role, description);
    }

    // a stack stamped with its kind: the use is the kind's wherever the item goes
    private static void goods(Tab tab, String id, String name, ItemStack stack, Price price, String role, String... description) {
        goods(tab, id, name, (g, p) -> stack, stack, price, role, description);
    }

    private static void goods(Tab tab, String id, String name, BiFunction<Game, Player, ItemStack> stack, ItemStack icon,
                              Price price, String role, String... description) {
        goods(tab, id, name, stack, (g, p) -> icon, icon.material(), price, role, description);
        PICKS.put(id, new Pick(id, List.of(plainOf(icon.material())), icon));
    }

    // the icon each viewer sees, and the material the role is filed under
    private static void goods(Tab tab, String id, String name, BiFunction<Game, Player, ItemStack> stack,
                              BiFunction<Game, Player, ItemStack> icon, Material plain, Price price, String role,
                              String... description) {
        ROLES.putIfAbsent(plain, role);
        PICKS.putIfAbsent(id, new Pick(id, List.of(plainOf(plain)), null));
        add(new Ware(Goods.of(id, price, role, stack), tab, List.of(name), List.of(icon), Items.gray(description), false));
    }

    private static void armor(String id, String name, Material icon, int tier, Price price, String... description) {
        add(new Ware(Rank.at(id, Gear.ARMOR, Rank.Holder.SEAT, tier, price), Tab.ARMOR, List.of(name),
                Ware.fixed(List.of(ItemStack.of(icon))), Items.gray(description), true));
    }

    private static void tool(String id, StateKey<Integer> ladder, List<ItemStack> tiers,
                             List<String> names) {
        Rank rank = Rank.next(id, ladder, Rank.Holder.SEAT,
                List.of(Price.of(10, IRON), Price.of(10, IRON), Price.of(3, GOLD), Price.of(6, GOLD)));
        add(new Ware(rank, Tab.TOOLS, names, Ware.fixed(tiers), List.of(), true));
        PICKS.put(id, new Pick(id, tiers.stream().map(ItemStack::material).toList(), null));
    }

    private static void potion(String id, String name, PotionType type, PotionEffect effect, int amplifier, int ticks,
                               boolean particles, Price price, String line) {
        ItemStack stack = ItemStack.of(Material.POTION).with(DataComponents.POTION_CONTENTS, new PotionContents(type, null,
                List.of(new CustomPotionEffect(effect, amplifier, ticks, false, particles, true))));
        ROLES.putIfAbsent(Material.POTION, "potions");
        PICKS.put(id, new Pick(id, List.of(Material.POTION), stack));
        add(new Ware(Goods.of(id, price, "potions", (g, p) -> stack), Tab.POTIONS, List.of(name), Ware.fixed(List.of(stack)),
                List.of(Component.text(line, NamedTextColor.BLUE)), false));
    }

    private static ItemStack enchanted(Material material, Map<RegistryKey<Enchantment>, Integer> enchants) {
        return ItemStack.of(material).with(DataComponents.ENCHANTMENTS, new EnchantmentList(enchants));
    }

    // the look's own family for a buyer with no team
    private static BiFunction<Game, Player, ItemStack> teamBlock(Role<Role.Fill> role, int count) {
        return (game, buyer) -> {
            Slot seat = game.roster().slotOf(buyer);
            return ItemStack.of(TeamBlocks.material(game, seat, role, BedWars.LOOK), count);
        };
    }

}
