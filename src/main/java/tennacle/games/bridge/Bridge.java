package tennacle.games.bridge;

import tennacle.Mechanics;
import tennacle.Msg;
import tennacle.games.Duels;
import tennacle.games.KitLayouts;
import tennacle.games.MapPool;

import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameType;
import io.github.term4.grotto.kit.Kit;
import io.github.term4.polyp.mechanics.damage.types.projectile.ProjectileDamage;
import io.github.term4.polyp.mechanics.hunger.HungerConfig;
import io.github.term4.polyp.mechanics.projectile.ProjectileConfig;
import io.github.term4.polyp.mechanics.consumable.ConsumableBehavior;
import io.github.term4.polyp.mechanics.consumable.ConsumableConfig;
import io.github.term4.polyp.mechanics.consumable.ConsumableConfigResolver;
import io.github.term4.polyp.mechanics.consumable.ConsumableTypeConfig;
import io.github.term4.polyp.mechanics.consumable.catalog.VanillaConsumables;
import io.github.term4.polyp.vri.VriConfig;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import io.github.term4.polyp.mechanics.projectile.types.Arrow;
import io.github.term4.polyp.mechanics.projectile.types.ProjectileTypeConfig;
import io.github.term4.grotto.roster.SlotLayout;
import io.github.term4.grotto.modules.Kits;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.grotto.vault.VaultKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.codec.Codec;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.Command;
import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.item.component.EnchantmentList;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.utils.Unit;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.util.List;
import java.util.Map;

/**
 * Bridge: red side, blue side, a goal pit behind each - jump into the ENEMY's to score, first to five wins.
 * Rounds start caged ({@link Cages}); building is gated to the map's build slices between the bridge's
 * underside and the height band; dying to the void respawns you behind brief protection ({@link Rules}).
 * Boards in {@link Boards}, chat and title text in {@link Banners}, end screens in {@link Ends}.
 */
public final class Bridge {

    /** Group = the DEFENDING team; scoring is entering the goal whose group is not yours. */
    public static final MarkerKey<RegionMarker> GOAL = MarkerKey.region("bridge:goal");
    /** The caged round-start spot per team (rounds begin and re-cage here); slots seat off it too. */
    public static final MarkerKey<PosMarker> CAGE = MarkerKey.pos("bridge:cage");
    /** Post-death spawn per team. */
    public static final MarkerKey<PosMarker> RESPAWN = MarkerKey.pos("bridge:respawn");
    /** Horizontal build slices (multi-box for non-square shapes); Y never gates here - see the band. */
    public static final MarkerKey<RegionMarker> BUILD = MarkerKey.region("bridge:build");
    /** Highest placeable Y - the starter ships y=99 (y=100, the texture-pack line, is the first refused row). */
    public static final MarkerKey<LimitMarker> HEIGHT = MarkerKey.limit("bridge:height");
    /** The kill plane - the starter ships y=80. */
    public static final MarkerKey<LimitMarker> VOID = MarkerKey.limit("bridge:void");
    // the Bloom-authored bridge geometry: the build band derives from these (underside .. top + height - 1)
    public static final MarkerKey<RegionMarker> SECTION = MarkerKey.region("section");
    public static final MarkerKey<RegionMarker> CENTER = MarkerKey.region("center");

    public static final StateKey<Integer> GOALS = StateKey.of("bridge:goals", Codec.INT, 0, true);
    public static final StateKey<Integer> TARGET = StateKey.of("bridge:target", Codec.INT, 5);
    public static final StateKey<Integer> LIMIT = StateKey.of("bridge:limit-ticks", Codec.INT, 15 * 60 * 20);
    public static final StateKey<Integer> KILLS = StateKey.of("bridge:kills", Codec.INT, 0, true);
    /** The player's cage cosmetic: a variant group in the cages arena; "" = default. */
    public static final VaultKey<String> CAGE_CHOICE = VaultKey.of("bridge:cage", Codec.STRING, "");
    /** Knockback immunity after a respawn; forfeited by swinging or touching blocks. */
    public static final StateKey<Integer> PROTECT = StateKey.of("bridge:protect-ticks", Codec.INT, 40);
    /** Your own arrow boosts you (KB + its damage); off = self hits are inert. */
    public static final StateKey<Boolean> BOW_BOOST = StateKey.of("bridge:bow-boost", Codec.BOOLEAN, true);
    /** Teammates can hurt each other; off = team hits are inert (matters from 2v2v2v2 up). */
    public static final StateKey<Boolean> FRIENDLY_FIRE = StateKey.of("bridge:friendly-fire", Codec.BOOLEAN, false);

    // explicit per-team blocks - the team NAME never dictates the material
    private static final Map<String, Material> BLOCKS = Map.of(
            "red", Material.RED_TERRACOTTA, "blue", Material.BLUE_TERRACOTTA,
            "green", Material.LIME_TERRACOTTA, "yellow", Material.YELLOW_TERRACOTTA);

    private static final ItemStack SWORD = ItemStack.of(Material.IRON_SWORD)
            .with(DataComponents.UNBREAKABLE, Unit.INSTANCE);
    private static final ItemStack BOW = ItemStack.of(Material.BOW)
            .with(DataComponents.UNBREAKABLE, Unit.INSTANCE);
    private static final ItemStack PICKAXE = ItemStack.of(Material.DIAMOND_PICKAXE)
            .with(DataComponents.ENCHANTMENTS, new EnchantmentList(Map.of(Enchantment.EFFICIENCY, 2)))
            .with(DataComponents.UNBREAKABLE, Unit.INSTANCE);

    static final Kit KIT = Kit.of("bridge")
            .item("sword", 0, SWORD)
            .item("bow", 1, BOW)
            .item("pickaxe", 2, PICKAXE)
            .item("blocks", 3, (g, seat) -> ItemStack.of(blocks(seat.team()), 64))
            .item("blocks2", 4, (g, seat) -> ItemStack.of(blocks(seat.team()), 64))
            .item("gapple", 5, ItemStack.of(Material.GOLDEN_APPLE, 8))
            .item("arrow", 9, ItemStack.of(Material.ARROW))
            .display("blocks", ItemStack.of(Material.WHITE_TERRACOTTA, 64))
            .display("blocks2", ItemStack.of(Material.WHITE_TERRACOTTA, 64))
            .layouts((g, p) -> KitLayouts.of(p, "bridge"))
            .dyedArmor(EquipmentSlot.CHESTPLATE, Material.LEATHER_CHESTPLATE)
            .dyedArmor(EquipmentSlot.LEGGINGS, Material.LEATHER_LEGGINGS)
            .dyedArmor(EquipmentSlot.BOOTS, Material.LEATHER_BOOTS);

    private static GameType TYPE;

    /** The installed type - the round harness launches on synthetic arenas through it. */
    static GameType type() {
        return TYPE;
    }

    private Bridge() {}

    public static void install() {
        MapPool arena = MapPool.of("bridge");
        TYPE = GameType.builder("bridge")
                .slots(SlotLayout.groups(CAGE, 1))
                .team("red", NamedTextColor.RED)
                .team("blue", NamedTextColor.BLUE)
                .expect(CAGE, 2)
                .expect(GOAL, 2)
                .expect(RESPAWN, 2)
                .expect(BUILD, 1)
                .expect(HEIGHT, 1)
                .expect(VOID, 1)
                .expect(SECTION, 2)
                .expect(CENTER, 1)
                .modules(Cages::new, () -> Kits.of(KIT), Rules::new,
                        () -> Duels.byLook(Boards::hypixel, Boards::minemen, Boards::scrims),
                        () -> Duels.byLook(Ends::hypixel, Ends::minemen, Ends::scrims))
                .build();
        Command cageCmd = new Command("cage");
        cageCmd.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            var names = Cages.variants().stream().map(v -> v.isEmpty() ? "default" : v).sorted().toList();
            String mine = Cages.choiceOf(p);
            Msg.tag(p, "cage", "cages: " + (names.isEmpty() ? "none authored yet" : String.join(", ", names))
                    + " - yours: " + (mine.isEmpty() ? "default" : mine));
        });
        var variantArg = ArgumentType.Word("variant");
        cageCmd.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            String pick = ctx.get(variantArg);
            String variant = pick.equals("default") ? "" : pick;
            if (!Cages.variants().contains(variant)) {
                Msg.err(p, "cage", "no cage named '" + pick + "' (/cage lists them)");
                return;
            }
            Cages.choose(p, variant);
            Msg.tag(p, "cage", "cage set to " + pick);
        }, variantArg);
        MinecraftServer.getCommandManager().register(cageCmd);
        KitLayouts.editable("bridge", KIT);

        Duels.kind(new Duels.Kind("bridge", TYPE, arena,
                game -> Mechanics.polyp.profiles().setWorld(game.shard(),
                        io.github.term4.polyp.MechanicsProfile.builder()
                                .set(io.github.term4.polyp.MechanicsKeys.DAMAGE,
                                        Duels.meleeAndOver(null, java.util.Set.of(ProjectileDamage.KEY)))
                                .set(io.github.term4.polyp.MechanicsKeys.PROJECTILES,
                                        arrowsVanishOver(null, Duels.lookNameOf(game)))
                                .set(io.github.term4.polyp.MechanicsKeys.HUNGER, hungerOff(null))
                                .set(io.github.term4.polyp.MechanicsKeys.VRI, NO_TOSS)
                                .set(io.github.term4.polyp.MechanicsKeys.CONSUMABLES, bridgeAppleOver(null))
                                .build()),
                Duels.SCRIMS,
                false, // no death animation or spectate: everyone stays where they stand
                List.of(Component.text("Cross the bridge to score goals.", NamedTextColor.YELLOW),
                        Component.text("Knock off your opponent to gain a clear path.", NamedTextColor.YELLOW),
                        Component.empty(),
                        Component.text("First player to score 5 goals wins!", NamedTextColor.YELLOW)),
                GOALS)); // a SCORING kind: hypixel ends on "<TEAM> WINS!" over the final score
    }

    // hypixel parity: mined clay drops and gets picked up - TOSSING is the only thing bridge removes
    private static final VriConfig NO_TOSS = VriConfig.builder().blockBreakProgress(true)
            .blockDrops(io.github.term4.polyp.vri.BlockDrops.VANILLA)
            .itemPickup(true).fireBreaks(true).tntIgnite(true).build();

    // the hypixel heal apple: full health, absorption I 60s only if absent - and nothing else (no regen)
    private static final ConsumableBehavior BRIDGE_APPLE = new ConsumableBehavior() {
        @Override
        public void onFinish(ConsumableConfigResolver.ConsumableContext ctx) {
            Player eater = ctx.user();
            eater.setHealth((float) eater.getAttributeValue(Attribute.MAX_HEALTH));
            boolean absorbed = eater.getActiveEffects().stream()
                    .anyMatch(effect -> effect.potion().effect().equals(PotionEffect.ABSORPTION));
            if (!absorbed) eater.addEffect(new Potion(PotionEffect.ABSORPTION, 0, 20 * 60));
        }
    };

    /** Replaces the vanilla gapple effects for this world; type entries layer whole, so the 1.8 eat gate rides along. */
    private static ConsumableConfig bridgeAppleOver(ConsumableConfig base) {
        if (base == null) {
            var global = Mechanics.polyp.profiles().global();
            base = global != null ? global.get(io.github.term4.polyp.MechanicsKeys.CONSUMABLES) : null;
        }
        ConsumableTypeConfig gapple = ConsumableTypeConfig.builder(VanillaConsumables.GOLDEN_APPLE.key())
                .canConsume(ctx -> VanillaConsumables.legacyCanEat(ctx, true))
                .behavior(BRIDGE_APPLE)
                .build();
        ConsumableTypeConfig baseGapple = base != null ? base.typeConfig(VanillaConsumables.GOLDEN_APPLE.key()) : null;
        if (baseGapple != null) gapple = gapple.fromBase(baseGapple); // eat time etc. stay preset-tuned
        ConsumableConfig apple = ConsumableConfig.builder().typeConfigs(gapple).build();
        return base != null ? apple.fromBase(base) : apple;
    }

    /**
     * Bridge arrows never stick - despawn on landing; rides over {@code base} like the damage gate. Bow crits
     * are per-look (captured 2026-08-31): hypixel and scrims drop the damage roll, minemen keeps vanilla, and
     * scrims still shows the trail - so the flag and the roll are set independently.
     */
    private static ProjectileConfig arrowsVanishOver(ProjectileConfig base, String look) {
        if (base == null) {
            var global = Mechanics.polyp.profiles().global();
            base = global != null ? global.get(io.github.term4.polyp.MechanicsKeys.PROJECTILES) : null;
        }
        // type entries layer WHOLE over base's - build from its arrow so the 1.8 tuning rides along
        ProjectileTypeConfig baseArrow = base != null ? base.typeConfig(Arrow.KEY) : null;
        ProjectileTypeConfig arrow = (baseArrow != null
                ? ProjectileTypeConfig.builder(baseArrow) : ProjectileTypeConfig.builder(Arrow.KEY))
                .removeOnBlockHit(true).stuckInBody(false) // no cosmetic body arrows in bridge
                .critDamage(!"hypixel".equals(look) && !"scrims".equals(look))
                .critParticles("scrims".equals(look) ? Boolean.TRUE : "hypixel".equals(look) ? Boolean.FALSE : null)
                .build();
        ProjectileConfig vanish = ProjectileConfig.builder().typeConfigs(arrow).build();
        return base != null ? vanish.fromBase(base) : vanish;
    }

    /** Food locked, healing only by apple: hunger and natural regen both off. */
    private static HungerConfig hungerOff(HungerConfig base) {
        if (base == null) {
            var global = Mechanics.polyp.profiles().global();
            base = global != null ? global.get(io.github.term4.polyp.MechanicsKeys.HUNGER) : null;
        }
        HungerConfig off = HungerConfig.builder().enabled(false).naturalRegen(false).build();
        return base != null ? off.fromBase(base) : off;
    }

    static String teamOf(Game game, Player player) {
        var seat = game.roster().slotOf(player);
        return seat != null ? seat.team() : "";
    }

    private static Material blocks(String team) {
        return BLOCKS.getOrDefault(team, Material.WHITE_TERRACOTTA);
    }
}
