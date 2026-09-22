package tennacle.games.bedfight;

import tennacle.Mechanics;
import tennacle.games.KitLayouts;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.map.MapPool;
import tennacle.games.matchmaking.Duels;
import tennacle.games.Flags;
import tennacle.games.matchmaking.Parties;
import tennacle.looks.Look;
import io.github.term4.grotto.roster.Team;
import tennacle.looks.Palette;
import tennacle.looks.Presentation;
import tennacle.games.Presets;
import io.github.term4.bloom.edit.Teams;

import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.canvas.theme.Livery;
import io.github.term4.canvas.theme.Role;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameType;
import io.github.term4.grotto.Markers;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.kit.Kit;
import io.github.term4.grotto.modules.combat.FriendlyFire;
import io.github.term4.grotto.modules.combat.Freeze;
import io.github.term4.grotto.Body;
import io.github.term4.grotto.modules.combat.KillCredit;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.modules.flow.Abandonment;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.modules.flow.Phases;
import io.github.term4.grotto.modules.gear.Kits;
import io.github.term4.grotto.modules.gear.Loadout;
import io.github.term4.grotto.modules.outcome.LastStanding;
import io.github.term4.grotto.modules.space.Limits;
import io.github.term4.grotto.modules.view.HealthDisplay;
import io.github.term4.grotto.modules.outcome.Pressure;
import io.github.term4.grotto.modules.view.Nametags;
import io.github.term4.grotto.modules.view.Refusals;
import io.github.term4.grotto.modules.view.Sight;
import io.github.term4.grotto.modules.view.Spectating;
import io.github.term4.grotto.roster.SlotLayout;
import net.minestom.server.codec.Codec;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.MechanicsProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.GameMode;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.EnchantmentList;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.utils.Unit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bed fight: a bed a side, and a wool bridge between them. While your bed stands you come back; break the
 * enemy's and their next death is their last, so the side that outlives the other wins. A side has one
 * {@code grotto:spawn}: the countdown holds it there and every respawn lands there, no cage. Building is gated
 * to the map's build region between its floor and its height, and the void plane below kills. The space rules
 * and the kills are {@link Rules}, the beds {@link Beds}, the board {@link Boards}. No match clock: what runs
 * out is {@link Beds#DESTROY}, and the last side standing wins however long that takes.
 */
public final class BedFight {

    /** The side's bed, both blocks, in the OWNING team's group. */
    public static final MarkerKey<RegionMarker> BED = MarkerKey.region("bedfight:bed");
    /** Where building is allowed at all: a flat box is an X/Z slice under the band, multi-box for odd shapes. */
    public static final MarkerKey<RegionMarker> BUILD = MarkerKey.region("bedfight:build");
    /** The map's own blocks a seat may take apart, in the OWNING team's group - the wool around a bed, multi-box
     *  for odd shapes. Everywhere else only what a seat placed itself comes back up. */
    public static final MarkerKey<RegionMarker> DEFENSE = MarkerKey.region("bedfight:defense");
    /** The line the map is symmetric about, for what asks which half a seat is standing in; a map naming none
     *  is read from the origin. Bloom's shared {@code center}, the same one bridge's arms sit either side of. */
    public static final MarkerKey<RegionMarker> CENTER = tennacle.games.map.Center.MARKER;
    /** Highest placeable Y. */
    public static final MarkerKey<LimitMarker> HEIGHT = MarkerKey.limit("bedfight:height");
    /** Lowest placeable Y. */
    public static final MarkerKey<LimitMarker> FLOOR = MarkerKey.limit("bedfight:floor");
    /** The kill plane. */
    public static final MarkerKey<LimitMarker> VOID = MarkerKey.limit("bedfight:void");

    public static final StateKey<Integer> KILLS = StateKey.of("ledger.bedfight.kills", Codec.INT, 0, true);
    public static final StateKey<Integer> DEATHS = StateKey.of("ledger.bedfight.deaths", Codec.INT, 0, true);
    /** Beds this seat broke. */
    public static final StateKey<Integer> BEDS = StateKey.of("ledger.bedfight.beds", Codec.INT, 0, true);

    private static final ItemStack SWORD = ItemStack.of(Material.WOODEN_SWORD)
            .with(DataComponents.UNBREAKABLE, Unit.INSTANCE);
    private static final ItemStack SHEARS = ItemStack.of(Material.SHEARS)
            .with(DataComponents.UNBREAKABLE, Unit.INSTANCE);
    private static final ItemStack PICKAXE = ItemStack.of(Material.WOODEN_PICKAXE)
            .with(DataComponents.ENCHANTMENTS, new EnchantmentList(Map.of(Enchantment.EFFICIENCY, 1)))
            .with(DataComponents.UNBREAKABLE, Unit.INSTANCE);
    private static final ItemStack AXE = ItemStack.of(Material.WOODEN_AXE)
            .with(DataComponents.ENCHANTMENTS, new EnchantmentList(Map.of(Enchantment.EFFICIENCY, 1)))
            .with(DataComponents.UNBREAKABLE, Unit.INSTANCE);

    /** Bed fight's look under an arena's own: what a side builds with is wool in its tint. */
    static final Livery LOOK = new Livery(Map.of("blocks", "wool"));
    static final Kit KIT = Kit.of("bedfight").look(LOOK)
            .item("sword", 0, SWORD)
            .item("pickaxe", 1, PICKAXE)
            .item("axe", 2, AXE)
            .item("shears", 3, SHEARS)
            .blocks("blocks", 4, 64)
            .layouts((g, p) -> KitLayouts.of(p, "bedfight"))
            // the helmet alone is enchanted: mining wool underwater is the same speed as out of it
            .dyedArmor(EquipmentSlot.HELMET, Material.LEATHER_HELMET, worn -> worn.with(DataComponents.ENCHANTMENTS,
                    new EnchantmentList(Map.of(Enchantment.AQUA_AFFINITY, 1))))
            .dyedArmor(EquipmentSlot.CHESTPLATE, Material.LEATHER_CHESTPLATE)
            .dyedArmor(EquipmentSlot.LEGGINGS, Material.LEATHER_LEGGINGS)
            .dyedArmor(EquipmentSlot.BOOTS, Material.LEATHER_BOOTS);

    /** What bed fight reads per team and what it says by default - the editor's and the launch check's view. */
    static final Teams.Defaults DEFAULTS = new Teams.Defaults("bedfight", LOOK, roles());

    private static List<Role<?>> roles() {
        List<Role<?>> roles = new ArrayList<>(KIT.roles());
        roles.add(io.github.term4.grotto.roster.Team.DISPLAY);
        return roles;
    }

    private static GameType TYPE;

    /** The installed type - the round harness launches on synthetic arenas through it. */
    static GameType type() {
        return TYPE;
    }

    private BedFight() {}

    public static void install() {
        MapPool arena = MapPool.of("bedfight");
        Teams.register(DEFAULTS);
        TYPE = GameType.builder("bedfight")
                .slots(SlotLayout.groups(Markers.SPAWN, Parties.MAX)) // a party shares its marker
                .teams(a -> Palette.teams(a, Markers.SPAWN, DEFAULTS))
                .expect(Markers.SPAWN, 2)
                .expect(BED, 2)
                .expect(DEFENSE, 2)
                .expect(BUILD, 1)
                .expect(HEIGHT, 1)
                .expect(FLOOR, 1)
                .expect(VOID, 1)
                .body(Body.fresh().gamemode(GameMode.SURVIVAL))
                .rule(Countdown.FROM, 3)
                .rule(tennacle.looks.Frames.CLOCK, List.of("green", "5:00 yellow", "2:00 red")) // the bed clock's own
                .rule(Loadout.DROP, false)   // the kit is the whole economy: it never leaves the body
                .rule(Loadout.UNEQUIP, false)
                .rule(Respawns.RESPAWN_TICKS, 60L)
                .rule(Respawns.REJOIN_TICKS, 120L) // coming back costs twice a death: walking out was the death
                .rule(Respawns.CLEAR, 2) // a spawn is buildable over; landing there takes the roof back down
                .rule(Respawns.PROTECT_TICKS, 40L) // knockback immunity after a respawn; a swing forfeits it
                .rule(KillCredit.TICKS, 100L)      // a shove credits the void kill for this long
                .modules(() -> Matches.byLook(Ends::hypixel, Ends::minemen, Ends::scrims, Ends::tennacle), // first: the snapshot precedes cleanup
                        () -> Phases.of(Match.OPENING, Match.LIVE, Match.OVER),
                        () -> Countdown.of().voice(Presentation::voice),
                        () -> Freeze.during(Match.OPENING).after(Match.OVER),
                        tennacle.games.map.Sections::new, // a base's dyed cells wear the side's palette, per match
                        () -> LastStanding.decides().requireAllSeatedAt(Match.LIVE),
                        Respawns::manual,
                        KillCredit::new,
                        FriendlyFire::new,
                        Beds::new,
                        () -> Limits.of().below(VOID, (p, plane) -> Matches.fell(p, (g, who) -> {
                            Rules rules = g.module(Rules.class);
                            if (rules != null) rules.fell(who);
                        })),
                        Pressure::of,
                        Nametags::of,
                        Spectating::new, Sight::new, Presets::new,
                        () -> HealthDisplay.of().inTab(g -> Matches.lookOf(g).tabHealth()),
                        () -> Kits.of(KIT),
                        Loadout::new,
                        Rules::new, Presentation::new,
                        () -> Refusals.of(Look::refusal),
                        Abandonment.endWhenDeserted(Outcome::aborted),
                        () -> Matches.byLook(Boards::hypixel, Boards::minemen, Boards::scrims, Boards::tennacle))
                .build();
        KitLayouts.editable("bedfight", KIT);
        // Beds keys its cells by team id, so a bed authored under any other group never breaks and the side is
        // unkillable - the count check alone cannot see that
        arena.requires(a -> {
            java.util.Set<String> teams = new java.util.HashSet<>();
            for (Team team : Palette.teams(a, Markers.SPAWN, DEFAULTS)) teams.add(team.id());
            for (String group : a.groups(BED)) {
                if (!teams.contains(group)) {
                    return "its '" + group + "' bed under a spawn group (the spawns are " + String.join(", ", teams) + ")";
                }
            }
            return null;
        });
                // a map whose authored tints do not parse throws at launch, taking the whole mode with it
        arena.requires(a -> {
            Palette.teams(a, Markers.SPAWN, DEFAULTS);
            return null;
        });
        Rulesets.check(Mechanics.polyp.profiles().global(), TYPE);
        // no teamScore: the last side standing wins
        Mode bedfight = Mode.of("bedfight", TYPE, arena, MechanicsProfile::builder, Rulesets.BASE, Rulesets.variants())
                .intro(Component.text("Bridge across and break the enemy bed.", NamedTextColor.YELLOW),
                        Component.text("While your bed stands you respawn.", NamedTextColor.YELLOW),
                        Component.empty(),
                        Component.text("Break their bed, then eliminate them!", NamedTextColor.YELLOW))
                .ghostLoser()
                .sided()
                .tabStats(new Mode.SeatStat("Kills", KILLS), new Mode.SeatStat("Deaths", DEATHS),
                        new Mode.SeatStat("Beds", BEDS))
                .marks(Marks::render)
                .build();
        Duels.mode(bedfight);
        Flags.offer(bedfight, "preset");
    }
}
