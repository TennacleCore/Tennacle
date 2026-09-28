package tennacle.games.bedwars;

import tennacle.boot.Mechanics;
import tennacle.games.KitLayouts;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.Presets;
import tennacle.games.beds.Beds;
import tennacle.games.beds.Lives;
import tennacle.games.map.Building;
import tennacle.games.map.MapPool;
import tennacle.games.matchmaking.Parties;
import tennacle.looks.Look;
import tennacle.looks.Palette;
import tennacle.looks.Presentation;
import io.github.term4.bloom.mapmaking.Teams;

import io.github.term4.barnacle.marker.DataMarker;
import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.canvas.theme.Livery;
import io.github.term4.canvas.theme.Role;
import io.github.term4.grotto.Body;
import io.github.term4.grotto.GameType;
import io.github.term4.grotto.Markers;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.kit.Kit;
import io.github.term4.grotto.modules.combat.FriendlyFire;
import io.github.term4.grotto.modules.combat.KillCredit;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.modules.flow.Abandonment;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.modules.flow.Phases;
import io.github.term4.grotto.modules.flow.Staging;
import io.github.term4.grotto.modules.gear.Kits;
import io.github.term4.grotto.modules.gear.Loadout;
import io.github.term4.grotto.modules.outcome.LastStanding;
import io.github.term4.grotto.modules.outcome.Pressure;
import io.github.term4.grotto.modules.space.Limits;
import io.github.term4.grotto.modules.view.HealthDisplay;
import io.github.term4.grotto.modules.view.Nametags;
import io.github.term4.grotto.modules.view.Refusals;
import io.github.term4.grotto.modules.view.Sight;
import io.github.term4.grotto.modules.view.Spectating;
import io.github.term4.grotto.roster.Roster;
import io.github.term4.grotto.roster.SlotLayout;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.MechanicsProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.codec.Codec;
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
 * Bed Wars, Hypixel's, on its maps: eight or four sides, each with a bed. A game fills up in the map's waiting
 * room and counts down one seat short of full; while your bed stands you respawn, and once it is broken your
 * next death is your last. Solo, Doubles, 3s and 4s are one game with a team size each, on the maps of their
 * team count. The first pass carries no generators or shop yet.
 */
public final class BedWars {

    /** The side's bed, both blocks, in the OWNING team's group. */
    public static final MarkerKey<RegionMarker> BED = MarkerKey.region("bedwars:bed");
    /** The side's base, where nothing is placed. */
    public static final MarkerKey<RegionMarker> BASE = MarkerKey.region("bedwars:base");
    /** The waiting room, gone at the start. */
    public static final MarkerKey<RegionMarker> LOBBY = MarkerKey.region("bedwars:lobby");
    /** Highest placeable Y. */
    public static final MarkerKey<LimitMarker> HEIGHT = MarkerKey.limit("bedwars:height");
    /** Lowest placeable Y. */
    public static final MarkerKey<LimitMarker> FLOOR = MarkerKey.limit("bedwars:floor");
    /** The kill plane. */
    public static final MarkerKey<LimitMarker> VOID = MarkerKey.limit("bedwars:void");
    /** {@code radius}: a block goes in while its distance from 0,0 is under it, at every height. */
    public static final MarkerKey<DataMarker> RADIUS = MarkerKey.data("bedwars:radius");

    public static final StateKey<Integer> KILLS = StateKey.of("ledger.bedwars.kills", Codec.INT, 0, true);
    public static final StateKey<Integer> DEATHS = StateKey.of("ledger.bedwars.deaths", Codec.INT, 0, true);
    public static final StateKey<Integer> FINAL_KILLS = StateKey.of("ledger.bedwars.final-kills", Codec.INT, 0, true);
    public static final StateKey<Integer> FINAL_DEATHS = StateKey.of("ledger.bedwars.final-deaths", Codec.INT, 0, true);
    /** Beds this seat broke. */
    public static final StateKey<Integer> BEDS = StateKey.of("ledger.bedwars.beds", Codec.INT, 0, true);
    static final Lives.Counters COUNTERS = new Lives.Counters(KILLS, DEATHS, FINAL_KILLS, FINAL_DEATHS);

    private static final ItemStack SWORD = ItemStack.of(Material.WOODEN_SWORD)
            .with(DataComponents.UNBREAKABLE, Unit.INSTANCE);

    static final Livery LOOK = new Livery(Map.of());
    // captured: the wooden sword and team leather, the helmet alone with aqua affinity
    static final Kit KIT = Kit.of("bedwars").look(LOOK)
            .item("sword", 0, SWORD)
            .layouts((g, p) -> KitLayouts.of(p, "bedwars"))
            .dyedArmor(EquipmentSlot.HELMET, Material.LEATHER_HELMET, worn -> worn.with(DataComponents.ENCHANTMENTS,
                    new EnchantmentList(Map.of(Enchantment.AQUA_AFFINITY, 1))))
            .dyedArmor(EquipmentSlot.CHESTPLATE, Material.LEATHER_CHESTPLATE)
            .dyedArmor(EquipmentSlot.LEGGINGS, Material.LEATHER_LEGGINGS)
            .dyedArmor(EquipmentSlot.BOOTS, Material.LEATHER_BOOTS);

    /** What Bed Wars reads per team - the editor's and the launch check's view. */
    static final Teams.Defaults DEFAULTS = new Teams.Defaults("bedwars", LOOK, roles());

    // captured: the start banner's lines
    private static final List<Component> INTRO = List.of(
            Component.text("Protect your bed and destroy the enemy beds.", NamedTextColor.YELLOW, TextDecoration.BOLD),
            Component.text("Upgrade yourself and your team by collecting", NamedTextColor.YELLOW, TextDecoration.BOLD),
            Component.text("Iron, Gold, Emerald and Diamond from generators", NamedTextColor.YELLOW, TextDecoration.BOLD),
            Component.text("to access powerful upgrades.", NamedTextColor.YELLOW, TextDecoration.BOLD));

    private BedWars() {}

    private static List<Role<?>> roles() {
        List<Role<?>> roles = new ArrayList<>(KIT.roles());
        roles.add(Team.DISPLAY);
        return roles;
    }

    public static void install() {
        Teams.register(DEFAULTS);
        KitLayouts.editable("bedwars", KIT);
        mode("bedwars-solo", "Bed Wars Solo", "Solo", "bedwars-solo", 8, 1);
        mode("bedwars-doubles", "Bed Wars Doubles", "Doubles", "bedwars-doubles", 8, 2);
        mode("bedwars-3s", "Bed Wars 3v3v3v3", "3v3v3v3", "bedwars-3v3v3v3", 4, 3);
        mode("bedwars-4s", "Bed Wars 4v4v4v4", "4v4v4v4", "bedwars-4v4v4v4", 4, 4);
    }

    // one game, a team size each: the modes differ in their rules and the maps their team count plays on
    private static void mode(String name, String title, String label, String tag, int teams, int size) {
        MapPool arena = MapPool.of(tag).under("bedwars")
                .requires(a -> a.meta().tags().contains("bedwars-guessed") ? "a capture: it is a guess throughout" : null);
        GameType type = GameType.builder(name)
                .slots(SlotLayout.groups(Markers.SPAWN, Parties.MAX))
                .teams(a -> Palette.teams(a, Markers.SPAWN, DEFAULTS))
                .expectEachTeam(BED)
                .expect(LOBBY, 1)
                .expect(HEIGHT, 1)
                .expect(FLOOR, 1)
                .expect(VOID, 1)
                .expect(RADIUS, 1)
                .body(Body.fresh().gamemode(GameMode.SURVIVAL))
                .rule(Roster.TEAM_SIZE, size)
                .rule(Countdown.START_AT, teams * size - 1) // captured: the count began one seat short of full
                .rule(Countdown.FROM, 20)
                .rule(Loadout.DROP, false)
                .rule(Loadout.UNEQUIP, false)
                .rule(Respawns.RESPAWN_TICKS, 100L) // captured: 5 s
                .rule(Respawns.REJOIN_TICKS, 200L)
                .rule(Respawns.PROTECT_TICKS, 60L) // captured: 3 s, your own attack ends it
                .rule(KillCredit.TICKS, 200L) // captured: the 10 s credit window
                .rule(Beds.DESTROY, -1L) // Bed Gone rides the timeline, later
                .rule(Building.BREAK, List.of(Building.PLACED)) // the map's own blocks never come up; the beds are Beds'
                .rule(Building.BREAK_OUTSIDE, List.of(Building.PLACED))
                .modules(Ends::hypixel, // first: the snapshot precedes cleanup
                        () -> Phases.of(Match.OPENING, Match.LIVE, Match.OVER),
                        () -> Countdown.of().voice(Presentation::voice),
                        () -> Staging.during(Match.OPENING).clear(LOBBY)
                                .seating((g, p) -> g.module(Kits.class).apply(p)),
                        () -> LastStanding.decides().requireSeatedAt(Match.LIVE, 2),
                        Respawns::manual,
                        KillCredit::new,
                        FriendlyFire::new,
                        () -> new Beds(BED, BEDS),
                        () -> new Lives(COUNTERS),
                        () -> Limits.of().below(VOID, (p, plane) -> Matches.fell(p, (g, who) -> {
                            Staging staging = g.module(Staging.class);
                            if (staging != null && staging.waiting()) { // off the room's edge: back up, no death
                                staging.recall(who);
                                return;
                            }
                            Lives lives = g.module(Lives.class);
                            if (lives != null) lives.fell(who);
                        })),
                        Pressure::of,
                        Nametags::of,
                        Spectating::new, Sight::new, Presets::new,
                        () -> HealthDisplay.of().inTab(g -> Matches.lookOf(g).tabHealth()),
                        () -> Kits.of(KIT).applyOnJoin(false), // the kit comes with the seat, not the room
                        Loadout::new,
                        Rules::new, Presentation::new,
                        () -> Refusals.of(Look::refusal),
                        Abandonment.endWhenDeserted(Outcome::aborted),
                        () -> Boards.hypixel(label))
                .build();
        Rulesets.check(Mechanics.polyp.profiles().global(), type);
        Mode mode = Mode.of(name, type, arena, MechanicsProfile::builder, Rulesets.BASE, Rulesets.variants())
                .title(title)
                .intro(INTRO.toArray(Component[]::new))
                .game()
                .ghostLoser()
                .sided()
                .look("hypixel")
                .tabStats(new Mode.SeatStat("Kills", KILLS), new Mode.SeatStat("Final Kills", FINAL_KILLS),
                        new Mode.SeatStat("Beds Broken", BEDS))
                .build();
        Matches.register(mode);
    }
}
