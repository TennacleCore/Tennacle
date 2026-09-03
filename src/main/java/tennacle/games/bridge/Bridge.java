package tennacle.games.bridge;

import io.github.term4.grotto.roster.Roster;
import java.util.ArrayList;
import io.github.term4.canvas.theme.Role;
import io.github.term4.canvas.theme.Livery;
import io.github.term4.bloom.Teams;
import tennacle.games.Presets;
import tennacle.Mechanics;
import tennacle.Msg;
import tennacle.games.Duels;
import tennacle.games.KitLayouts;
import tennacle.games.MapPool;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.Presentation;

import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Body;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameType;
import io.github.term4.grotto.kit.Kit;
import io.github.term4.grotto.modules.Freeze;
import io.github.term4.grotto.modules.HealthDisplay;
import io.github.term4.grotto.modules.HitReport;
import io.github.term4.grotto.modules.Nametags;
import io.github.term4.grotto.modules.Spectating;
import io.github.term4.grotto.modules.FriendlyFire;
import io.github.term4.grotto.modules.Goals;
import io.github.term4.grotto.modules.KillCredit;
import io.github.term4.grotto.modules.Kits;
import io.github.term4.grotto.modules.LastStanding;
import io.github.term4.grotto.modules.Limits;
import io.github.term4.grotto.modules.Phases;
import io.github.term4.grotto.modules.Respawns;
import io.github.term4.grotto.modules.Restock;
import io.github.term4.grotto.modules.Score;
import io.github.term4.grotto.modules.TimeLimit;
import io.github.term4.grotto.roster.SlotLayout;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.grotto.vault.VaultKey;
import io.github.term4.polyp.mechanics.damage.types.projectile.ProjectileDamage;
import io.github.term4.polyp.mechanics.projectile.types.Arrow;
import io.github.term4.polyp.MechanicsProfile;
import io.github.term4.polyp.config.FieldFns;
import io.github.term4.polyp.mechanics.consumable.ConsumableBehavior;
import io.github.term4.polyp.mechanics.consumable.ConsumableConfigResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Codec;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.EnchantmentList;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.utils.Unit;

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
    public static final StateKey<Integer> KILLS = StateKey.of("bridge:kills", Codec.INT, 0, true);
    /** The player's cage cosmetic: a variant group in the cages arena; "" = default. */
    public static final VaultKey<String> CAGE_CHOICE = VaultKey.of("bridge:cage", Codec.STRING, "");

    private static final ItemStack SWORD = ItemStack.of(Material.IRON_SWORD)
            .with(DataComponents.UNBREAKABLE, Unit.INSTANCE);
    private static final ItemStack BOW = ItemStack.of(Material.BOW)
            .with(DataComponents.UNBREAKABLE, Unit.INSTANCE);
    private static final ItemStack PICKAXE = ItemStack.of(Material.DIAMOND_PICKAXE)
            .with(DataComponents.ENCHANTMENTS, new EnchantmentList(Map.of(Enchantment.EFFICIENCY, 2)))
            .with(DataComponents.UNBREAKABLE, Unit.INSTANCE);
    /** Bridge's look under an arena's own: blocks are terracotta in the team's tint. */
    static final Livery LOOK = new Livery(Map.of("blocks", "terracotta"));
    static final Kit KIT = Kit.of("bridge").look(LOOK)
            .item("sword", 0, SWORD)
            .item("bow", 1, BOW)
            .item("pickaxe", 2, PICKAXE)
            .blocks("blocks.1", 3, 64)
            .blocks("blocks.2", 4, 64)
            .item("gapple", 5, ItemStack.of(Material.GOLDEN_APPLE, 8))
            .item("arrow", 9, ItemStack.of(Material.ARROW))
            .layouts((g, p) -> KitLayouts.of(p, "bridge"))
            .dyedArmor(EquipmentSlot.CHESTPLATE, Material.LEATHER_CHESTPLATE)
            .dyedArmor(EquipmentSlot.LEGGINGS, Material.LEATHER_LEGGINGS)
            .dyedArmor(EquipmentSlot.BOOTS, Material.LEATHER_BOOTS);
    /** What bridge reads per team and what it says by default - the editor's and the launch check's view of it. */
    static final Teams.Defaults DEFAULTS = new Teams.Defaults("bridge", LOOK, roles());

    private static List<Role<?>> roles() {
        List<Role<?>> roles = new ArrayList<>(KIT.roles());
        roles.add(Cages.CAGE_TINT);
        roles.add(io.github.term4.grotto.roster.Team.DISPLAY);
        return roles;
    }

    /**
     * The hypixel heal apple: full health, and Absorption I for a minute only if the eater has none. Code,
     * because it is game logic rather than a knob - {@code registerFactories} NAMES it so a ruleset can point
     * the golden apple at it without anyone writing behaviour as data.
     */
    private static final ConsumableBehavior HEAL_APPLE = new ConsumableBehavior() {
        @Override
        public void onFinish(ConsumableConfigResolver.ConsumableContext ctx) {
            Player eater = ctx.user();
            eater.setHealth((float) eater.getAttributeValue(Attribute.MAX_HEALTH));
            boolean absorbed = eater.getActiveEffects().stream()
                    .anyMatch(effect -> effect.potion().effect().equals(PotionEffect.ABSORPTION));
            if (!absorbed) eater.addEffect(new Potion(PotionEffect.ABSORPTION, 0, 20 * 60));
        }
    };

    private static GameType TYPE;

    /** The installed type - the round harness launches on synthetic arenas through it. */
    static GameType type() {
        return TYPE;
    }

    private Bridge() {}

    public static void install() {
        // the named definitions this mode's rules may point at
        if (!FieldFns.names(ConsumableBehavior.class).contains("heal-apple")) { // re-installs (tests) must not clash
            FieldFns.register(ConsumableBehavior.class, "heal-apple",
                    "full health, plus Absorption I for a minute when the eater has none", args -> HEAL_APPLE);
        }
        MapPool arena = MapPool.of("bridge");
        Teams.register(DEFAULTS); // the editor shows an arena's effective look
        TYPE = GameType.builder("bridge")
                .slots(SlotLayout.groups(CAGE, 1))
                .teams(a -> tennacle.games.Palette.teams(a, CAGE, DEFAULTS))
                .expect(CAGE, 2)
                .expect(GOAL, 2)
                .expect(RESPAWN, 2)
                .expect(BUILD, 1)
                .expect(HEIGHT, 1)
                .expect(VOID, 1)
                .expect(SECTION, 2)
                .expect(CENTER, 1)
                .body(Body.fresh().gamemode(GameMode.SURVIVAL)) // bridge digs and builds
                .rule(Score.TARGET, 5)
                .rule(TimeLimit.LIMIT, 15L * 60 * 20)
                .rule(TimeLimit.TIEBREAKER, false) // ranked turns this on: a drawn clock plays on
                .rule(Respawns.PROTECT_TICKS, 40L) // knockback immunity after a respawn; a swing or a block touch forfeits it
                .rule(KillCredit.TICKS, 100L)      // a shove credits the void kill for this long
                .rule(Roster.RECLAIM_TICKS, 30L * 20) // a dropped player's seat holds this long for /rejoin
                .modules(() -> Phases.of(Match.OPENING, Match.LIVE, Match.OVER),
                        Cages::new, // the opener, and the re-cage after a goal
                        () -> Freeze.during(Match.OPENING).after(Match.OVER),
                        () -> LastStanding.decides().requireAllSeatedAt(Match.LIVE),
                        () -> Score.teams(GOALS).target(Score.TARGET),
                        () -> TimeLimit.of().from(Match.OPENING).onExpire(TimeLimit.highestTeamScoreWins(GOALS))
                                .tiebreaker(Rules::tiebreaker),
                        () -> Respawns.manual().anchor(RESPAWN),
                        KillCredit::new,
                        FriendlyFire::new,
                        () -> Goals.of(GOAL).counter(GOALS).during(Match.LIVE),
                        () -> Limits.of().below(VOID, (p, plane) -> {
                            Game game = Game.of(p);
                            Rules rules = game != null ? game.module(Rules.class) : null;
                            if (rules != null) rules.fell(p);
                        }),
                        Nametags::of,
                        Spectating::new, Presets::new,
                        () -> HealthDisplay.of().inTab(g -> Matches.lookOf(g).tabHealth()),
                        () -> HitReport.of().types(ProjectileDamage.KEY).line(g -> Matches.lookOf(g).hit()), // arrows are thrown-typed
                        () -> Kits.of(KIT),
                        () -> Restock.of("arrow", Arrow.class, 70L), // 3.5s: the termbridge/hypixel return
                        Rules::new, Presentation::new,
                        () -> Matches.byLook(Boards::hypixel, Boards::minemen, Boards::scrims),
                        () -> Matches.byLook(Ends::hypixel, Ends::minemen, Ends::scrims))
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
        Rulesets.check(Mechanics.polyp.profiles().global(), TYPE);
        Duels.mode(new Mode("bridge", TYPE, arena, MechanicsProfile::builder, Rulesets.BASE, Rulesets.variants(), "scrims",
                List.of(Component.text("Cross the bridge to score goals.", NamedTextColor.YELLOW),
                        Component.text("Knock off your opponent to gain a clear path.", NamedTextColor.YELLOW),
                        Component.empty(),
                        Component.text("First player to score 5 goals wins!", NamedTextColor.YELLOW)),
                false, // no death animation or spectate: everyone stays where they stand
                GOALS)); // a SCORING mode: hypixel ends on "<TEAM> WINS!" over the final score
    }

    static String teamOf(Game game, Player player) {
        return Match.teamOf(game, player);
    }
}
