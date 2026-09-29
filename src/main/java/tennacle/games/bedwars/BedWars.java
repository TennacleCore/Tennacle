package tennacle.games.bedwars;

import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import net.minestom.server.command.CommandSender;
import io.github.term4.grotto.vault.VaultKey;
import io.github.term4.grotto.modules.outcome.Stats;
import tennacle.Msg;
import tennacle.games.Careers;
import tennacle.boot.Mechanics;
import tennacle.games.Generators;
import tennacle.games.Idle;
import tennacle.games.Invisibles;
import tennacle.games.Keepers;
import tennacle.games.KitLayouts;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.Presets;
import tennacle.games.Skins;
import tennacle.games.Timeline;
import tennacle.games.beds.Beds;
import tennacle.games.beds.Marks;
import tennacle.games.beds.Lives;
import tennacle.games.beds.Spoils;
import tennacle.games.map.HotArena;
import tennacle.games.map.Rooms;
import tennacle.games.TeamChat;
import tennacle.games.HiddenNames;
import tennacle.games.map.Building;
import tennacle.games.map.Structures;
import tennacle.games.map.MapPool;
import tennacle.games.matchmaking.Parties;
import tennacle.games.shop.Favorites;
import tennacle.games.shop.Shop;
import tennacle.looks.Look;
import tennacle.looks.Palette;
import tennacle.looks.Presentation;
import io.github.term4.bloom.mapmaking.Teams;

import io.github.term4.barnacle.marker.DataMarker;
import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.PointMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import tennacle.games.TeamBlocks;
import tennacle.games.items.BridgeEgg;
import tennacle.games.items.Creatures;
import tennacle.games.items.TntMark;
import tennacle.games.items.Fireballs;
import tennacle.games.items.PopupTower;
import tennacle.games.items.Sponge;
import tennacle.games.matchmaking.PartyExit;
import io.github.term4.canvas.theme.Livery;
import io.github.term4.canvas.theme.Role;
import io.github.term4.grotto.Body;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameType;
import io.github.term4.grotto.Markers;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.kit.Kit;
import io.github.term4.grotto.modules.combat.FriendlyFire;
import io.github.term4.grotto.modules.combat.KillCredit;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.modules.flow.Abandonment;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.modules.flow.Deciders;
import io.github.term4.grotto.modules.flow.Phases;
import io.github.term4.grotto.modules.flow.Staging;
import io.github.term4.grotto.modules.gear.Kits;
import io.github.term4.grotto.modules.gear.Loadout;
import io.github.term4.grotto.modules.outcome.LastStanding;
import io.github.term4.grotto.modules.outcome.Pressure;
import io.github.term4.grotto.modules.space.Limits;
import io.github.term4.grotto.modules.space.Regions;
import io.github.term4.grotto.modules.view.HealthDisplay;
import io.github.term4.grotto.modules.view.Nametags;
import io.github.term4.grotto.modules.view.Refusals;
import io.github.term4.grotto.modules.view.Sight;
import io.github.term4.grotto.modules.view.Spectating;
import io.github.term4.grotto.roster.Roster;
import io.github.term4.grotto.roster.SlotLayout;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.MechanicsProfile;
import io.github.term4.polyp.config.KeySet;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.codec.Codec;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.VillagerProfession;
import net.minestom.server.entity.VillagerType;
import net.minestom.server.entity.metadata.villager.VillagerMeta;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.EnchantmentList;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bed Wars, Hypixel's, on its maps: eight or four sides, each with a bed. A game fills up in the map's waiting
 * room and counts down one seat short of full; while your bed stands you respawn, and once it is broken your
 * next death is your last. Solo, Doubles, 3s and 4s are one game with a team size each, on the maps of their
 * team count. The forges and the middle's generators run on the captured schedules.
 */
public final class BedWars {

    /** The side's bed, both blocks, in the OWNING team's group. */
    public static final MarkerKey<RegionMarker> BED = MarkerKey.region("bedwars:bed");
    /** The side's base, where nothing is placed. */
    public static final MarkerKey<RegionMarker> BASE = MarkerKey.region("bedwars:base");
    /** The waiting room, gone at the start. */
    public static final MarkerKey<RegionMarker> LOBBY = MarkerKey.region("bedwars:lobby");
    /** The waiting room, once, in its own arena; the maps carry the marker alone. */
    static final HotArena ROOMS = HotArena.of("rooms");
    /** Highest placeable Y. */
    public static final MarkerKey<LimitMarker> HEIGHT = MarkerKey.limit("bedwars:height");
    /** Lowest placeable Y. */
    public static final MarkerKey<LimitMarker> FLOOR = MarkerKey.limit("bedwars:floor");
    /** The kill plane. */
    public static final MarkerKey<LimitMarker> VOID = MarkerKey.limit("bedwars:void");
    /** {@code radius}: a block goes in while its distance from 0,0 is under it, at every height. */
    public static final MarkerKey<DataMarker> RADIUS = MarkerKey.data("bedwars:radius");
    /** Where the side's gold and emeralds drop. */
    public static final MarkerKey<PointMarker> FORGE = MarkerKey.point("bedwars:forge");
    /** Where its iron drops; the payload counts each mode's spawners there. */
    public static final MarkerKey<PointMarker> FORGE_IRON = MarkerKey.point("bedwars:forge-iron");
    public static final MarkerKey<PointMarker> DIAMOND = MarkerKey.point("bedwars:diamond");
    public static final MarkerKey<PointMarker> EMERALD = MarkerKey.point("bedwars:emerald");
    /** Where the side's item shopkeeper stands. */
    public static final MarkerKey<PointMarker> SHOP = MarkerKey.point("bedwars:shop");
    /** Where the side's upgrades shopkeeper stands. */
    public static final MarkerKey<PointMarker> UPGRADES = MarkerKey.point("bedwars:upgrades");

    public static final StateKey<Integer> KILLS = StateKey.of("ledger.bedwars.kills", Codec.INT, 0, true);
    public static final StateKey<Integer> DEATHS = StateKey.of("ledger.bedwars.deaths", Codec.INT, 0, true);
    public static final StateKey<Integer> FINAL_KILLS = StateKey.of("ledger.bedwars.final-kills", Codec.INT, 0, true);
    public static final StateKey<Integer> FINAL_DEATHS = StateKey.of("ledger.bedwars.final-deaths", Codec.INT, 0, true);
    /** Beds this seat broke. */
    public static final StateKey<Integer> BEDS = StateKey.of("ledger.bedwars.beds", Codec.INT, 0, true);
    static final Lives.Counters COUNTERS = new Lives.Counters(KILLS, DEATHS, FINAL_KILLS, FINAL_DEATHS);

    // career truth, in the vault
    public static final VaultKey<Integer> CAREER_KILLS = VaultKey.of("bedwars:career-kills", Codec.INT, 0);
    public static final VaultKey<Integer> CAREER_DEATHS = VaultKey.of("bedwars:career-deaths", Codec.INT, 0);
    public static final VaultKey<Integer> CAREER_FINAL_KILLS = VaultKey.of("bedwars:career-final-kills", Codec.INT, 0);
    public static final VaultKey<Integer> CAREER_FINAL_DEATHS = VaultKey.of("bedwars:career-final-deaths", Codec.INT, 0);
    public static final VaultKey<Integer> CAREER_BEDS = VaultKey.of("bedwars:career-beds", Codec.INT, 0);
    public static final VaultKey<Integer> CAREER_WINS = VaultKey.of("bedwars:career-wins", Codec.INT, 0);
    public static final VaultKey<Integer> CAREER_LOSSES = VaultKey.of("bedwars:career-losses", Codec.INT, 0);

    private static final ItemStack SWORD = Kit.unbreakable(ItemStack.of(Material.WOODEN_SWORD));

    // the shop's team blocks, in the team's color
    static final Livery LOOK = new Livery(Map.of(TeamBlocks.WOOL.name(), "wool", TeamBlocks.CLAY.name(), "terracotta",
            TeamBlocks.GLASS.name(), "stained_glass"));
    // captured: the wooden sword and team leather, the helmet alone with aqua affinity; what is bought for good
    // comes back at every spawn, and the Hotbar Manager's categories are the layout's names
    static final Kit KIT = Kit.of("bedwars").look(LOOK)
            .item("melee", 0, SWORD)
            .item(Compass.SLOT, Compass.ITEM)
            .fallback("melee", Gear::sword) // captured 2026-09-27: the wooden sword is there whenever no better one is
            .roleOf(Wares::role)
            .ranked("melee", Gear::rank) // the user, 2026-09-28: a better sword takes the melee slot from the sword there; the beaten one to the first empty
            .item("pickaxe", Gear::pickaxe, Gear.PICKAXE)
            .item("axe", Gear::axe, Gear.AXE)
            .item("shears", Gear::shears, Gear.SHEARS)
            .role("blocks", ItemStack.of(Material.TERRACOTTA))
            .role("ranged", ItemStack.of(Material.BOW))
            .role("potions", ItemStack.of(Material.BREWING_STAND))
            .role("utility", ItemStack.of(Material.TNT))
            .layouts((g, p) -> KitLayouts.of(p, "bedwars"))
            .skin(Skins.skin())
            .stamp(Kit.UNBREAKABLE)
            .stamp(Upgrades::sharpened, Upgrades.SHARPNESS)
            .stamp(Upgrades::reinforced, Upgrades.PROTECTION)
            .stamp(Upgrades::cushioned, Upgrades.CUSHIONED)
            .effect(PotionEffect.HASTE, Upgrades::haste, Upgrades.HASTE)
            .dyedArmor(EquipmentSlot.HELMET, Material.LEATHER_HELMET, worn -> worn.with(DataComponents.ENCHANTMENTS,
                    new EnchantmentList(Map.of(Enchantment.AQUA_AFFINITY, 1))))
            .dyedArmor(EquipmentSlot.CHESTPLATE, Material.LEATHER_CHESTPLATE)
            .dyedArmor(EquipmentSlot.LEGGINGS, Gear::legs, Gear.ARMOR)
            .dyedArmor(EquipmentSlot.BOOTS, Gear::boots, Gear.ARMOR);

    /** Captured: every plank and log is one wood, oak planks when nobody wears it. */
    static final Skins.Family WOOD = Skins.register(new Skins.Family("wood", Material.OAK_PLANKS, wood()));

    /** What Bed Wars reads per team - the editor's and the launch check's view. */
    static final Teams.Defaults DEFAULTS = new Teams.Defaults("bedwars", LOOK, roles());

    // captured: the start banner's lines
    private static final List<Component> INTRO = List.of(
            Component.text("Protect your bed and destroy the enemy beds.", NamedTextColor.YELLOW, TextDecoration.BOLD),
            Component.text("Upgrade yourself and your team by collecting", NamedTextColor.YELLOW, TextDecoration.BOLD),
            Component.text("Iron, Gold, Emerald and Diamond from generators", NamedTextColor.YELLOW, TextDecoration.BOLD),
            Component.text("to access powerful upgrades.", NamedTextColor.YELLOW, TextDecoration.BOLD));

    private BedWars() {}

    private static Set<Material> wood() {
        Set<Material> out = new LinkedHashSet<>();
        for (String kind : List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak")) {
            out.add(Material.fromKey("minecraft:" + kind + "_planks"));
            out.add(Material.fromKey("minecraft:" + kind + "_log"));
        }
        return out;
    }

    private static List<Role<?>> roles() {
        List<Role<?>> roles = new ArrayList<>(KIT.roles());
        roles.addAll(List.of(Team.DISPLAY, TeamBlocks.WOOL, TeamBlocks.CLAY, TeamBlocks.GLASS));
        return roles;
    }

    // captured: a blacksmith, which Via shows a 1.8 client for an armorer
    private static Entity shopkeeper() {
        LivingEntity keeper = new LivingEntity(EntityType.VILLAGER);
        keeper.editEntityMeta(VillagerMeta.class, meta -> meta.setVillagerData(new VillagerMeta.VillagerData(
                VillagerType.PLAINS, VillagerProfession.ARMORER, VillagerMeta.Level.NOVICE)));
        return keeper;
    }

    private static void shop(Game game, Player player, PointMarker at) {
        if (open(game, player)) ItemShop.open(game, player, Wares.Tab.QUICK_BUY);
    }

    private static void upgrades(Game game, Player player, PointMarker at) {
        if (open(game, player)) UpgradeShop.open(game, player);
    }

    private static boolean open(Game game, Player player) {
        return Match.live(game) && game.roster().slotOf(player) != null && !game.isGhost(player);
    }

    // one shop a game: the wares, the team upgrades and the traps
    private static Shop shop() {
        Shop shop = Wares.shop();
        Upgrades.ALL.forEach(upgrade -> shop.offer(upgrade.offer()));
        Traps.offers().forEach(shop::offer);
        return shop;
    }

    public static void install() {
        Teams.register(DEFAULTS);
        KitLayouts.editable("bedwars", KIT);
        Favorites.install();
        Skins.install();
        mode("bedwars-solo", "Bed Wars Solo", "Solo", "solo", "bedwars-solo", 8, 1);
        mode("bedwars-doubles", "Bed Wars Doubles", "Doubles", "doubles", "bedwars-doubles", 8, 2);
        mode("bedwars-3s", "Bed Wars 3v3v3v3", "3v3v3v3", "3v3v3v3", "bedwars-3v3v3v3", 4, 3);
        mode("bedwars-4s", "Bed Wars 4v4v4v4", "4v4v4v4", "4v4v4v4", "bedwars-4v4v4v4", 4, 4);
        Rooms.warm(ROOMS); // off the boot thread, not off the first game's clock
        Careers.sheet("bedwars", BedWars::career);
    }

    private static CompletableFuture<Void> career(CommandSender to, UUID uuid, String username) {
        return Careers.read(Matches.grotto().vault(), uuid, List.of(CAREER_WINS, CAREER_LOSSES, CAREER_KILLS, CAREER_DEATHS,
                CAREER_FINAL_KILLS, CAREER_FINAL_DEATHS, CAREER_BEDS)).thenAccept(v -> Msg.say(to, Msg.v(username), " Bed Wars: ",
                Msg.v(v[0]), " wins, ", Msg.v(v[1]), " losses, ", Msg.v(v[2]), " kills, ", Msg.v(v[3]), " deaths, ",
                Msg.v(v[4]), " final kills, ", Msg.v(v[5]), " final deaths, ", Msg.v(v[6]), " beds broken."));
    }

    // captured: the timeline's events, the middle's tiers among them
    private static Timeline timeline() {
        return Timeline.of()
                .event("diamond-2", "Diamond II", 6L * 60 * 20).on("diamond-2", g -> tier(g, Mid.Kind.DIAMOND, 2))
                .event("emerald-2", "Emerald II", 12L * 60 * 20).on("emerald-2", g -> tier(g, Mid.Kind.EMERALD, 2))
                .event("diamond-3", "Diamond III", 18L * 60 * 20).on("diamond-3", g -> tier(g, Mid.Kind.DIAMOND, 3))
                .event("emerald-3", "Emerald III", 24L * 60 * 20).on("emerald-3", g -> tier(g, Mid.Kind.EMERALD, 3))
                .watched("bed-gone", "Bed Gone", g -> Beds.standing(g).isEmpty() ? -1 : Beds.fallsIn(g))
                .event("sudden-death", "Sudden Death", 40L * 60 * 20).on("sudden-death", Lines::suddenDeath)
                .on("sudden-death", Dragons::suddenDeath)
                .event("more-dragons", "More Dragons", 45L * 60 * 20).on("more-dragons", Dragons::moreDragons)
                .event("game-end", "Game End", 50L * 60 * 20).on("game-end", BedWars::end);
    }

    private static void tier(Game game, Mid.Kind kind, int tier) {
        Mid mid = game.module(Mid.class);
        if (mid != null) mid.tier(kind, tier);
        Lines.tierUp(game, kind, tier);
    }

    // captured: at 50:00 the side with the most alive wins; ours breaks a tie on final kills, beds, then kills,
    // and a tie past those is a draw
    private static void end(Game game) {
        LastStanding standing = game.module(LastStanding.class);
        if (standing == null || game.decided() != null) return;
        Comparator<String> order = Comparator.<String>comparingInt(standing::standing)
                .thenComparingInt(t -> sum(game, t, FINAL_KILLS))
                .thenComparingInt(t -> sum(game, t, BEDS))
                .thenComparingInt(t -> sum(game, t, KILLS));
        List<String> teams = new ArrayList<>(game.roster().teams().stream().map(Team::id).filter(standing::stands).toList());
        teams.sort(order.reversed());
        boolean tied = teams.size() > 1 && order.compare(teams.get(0), teams.get(1)) == 0;
        game.decide(teams.isEmpty() || tied ? Outcome.draw() : Outcome.winner(teams.getFirst()), Deciders.linger(game));
    }

    private static int sum(Game game, String team, StateKey<Integer> key) {
        int total = 0;
        for (var seat : game.roster().seatsOf(team)) total += game.state().get(Scope.slot(seat.index()), key);
        return total;
    }

    // captured: the four-team forge and middle run their own periods and caps; the Emerald Forge cap is a guess
    // (its emeralds outlast any cap under six), Molten's a capture
    private static void fourTeam(GameType.Builder builder) {
        builder.rule(Forges.IRON_PERIOD, List.of(61L, 45L, 31L, 31L, 31L))
                .rule(Forges.GOLD_PERIOD, List.of(151L, 113L, 76L, 76L, 76L))
                .rule(Forges.EMERALD_PERIOD, List.of(0L, 0L, 0L, 1101L, 541L))
                .rule(Forges.EMERALD_CAP, List.of(0, 0, 0, 7, 7))
                .rule(Forges.BOOST_IRON, 31L)
                .rule(Forges.BOOST_GOLD, 76L)
                .rule(Mid.Kind.EMERALD.period, List.of(1101L, 801L, 548L))
                .rule(Mid.Kind.DIAMOND.cap, 8)
                .rule(Mid.Kind.EMERALD.cap, 6);
        Wares.fourTeam(builder);
        Upgrades.fourTeam(builder);
    }

    // one game, a team size each: the modes differ in their rules and the maps their team count plays on
    private static void mode(String name, String title, String label, String spawners, String tag, int teams, int size) {
        MapPool arena = MapPool.of(tag).under("bedwars")
                .requires(a -> a.meta().tags().contains("bedwars-guessed") ? "a capture: it is a guess throughout" : null);
        GameType.Builder builder = GameType.builder(name)
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
                .rule(Loadout.UNEQUIP, false)
                .rule(Respawns.RESPAWN_TICKS, 100L) // captured: 5 s
                .rule(Respawns.WAIT, Respawns.WAIT_LANDING) // captured: the wait hangs at the pregame spawn over the middle
                .rule(Respawns.REJOIN_TICKS, 200L)
                .rule(Respawns.PROTECT_TICKS, 60L) // captured: 3 s, your own attack ends it
                .rule(KillCredit.TICKS, 200L) // captured: the 10 s credit window
                .rule(Spoils.TO_KILLER, KeySet.only(Resources.IRON.material().key(), Resources.GOLD.material().key(),
                        Resources.DIAMOND.material().key(), Resources.EMERALD.material().key())) // captured, in this order
                .rule(Beds.DESTROY, 30L * 60 * 20) // Bed Gone
                .rule(Beds.SOUND_LISTENER, true) // captured: both bed sounds at the listener's own block
                .rule(Building.FLOOR_DROPS, false) // nothing drops below the build floor; the inventory stays usable (the user, 2026-09-29)
                .rule(TeamChat.TEAM, size > 1) // Solo chat is global; a team mode talks to its team, /shout to all
                .rule(HiddenNames.HIDDEN, true) // a public pregame: strangers read as random strings
                .rule(HiddenNames.SKINS, true)
                .rule(HealthDisplay.FROM, Match.LIVE) // captured: health under names and in the tab comes with the start
                .rule(Staging.DRAW, true) // captured: no team shows until the start, a party lands together
                .rule(Building.BREAK, List.of(Building.PLACED, Building.GRASS)) // the map's blocks never come up, its plants do; the beds are Beds'
                .rule(Building.BLAST, List.of(Building.PLACED)) // a fireball or TNT takes placed blocks, never the map
                .rule(Building.BREAK_OUTSIDE, List.of(Building.PLACED, Building.GRASS));
        if (teams == 4) fourTeam(builder);
        GameType type = builder.modules(Ends::hypixel, // first: the snapshot precedes cleanup
                        () -> Phases.of(Match.OPENING, Match.LIVE, Match.OVER),
                        () -> Countdown.of().voice(Presentation::voice),
                        BedWars::timeline,
                        // captured: a teammate within about 2.5 blocks of forge iron or gold picked up gets a copy
                        () -> Generators.of().share(2.5, stack -> stack.material() == Material.IRON_INGOT
                                || stack.material() == Material.GOLD_INGOT),
                        () -> new Forges(spawners),
                        Mid::new,
                        BedWars::shop,
                        () -> Keepers.of().at(SHOP, BedWars::shopkeeper, BedWars::shop, Lines::shopLabels)
                                .at(UPGRADES, BedWars::shopkeeper, BedWars::upgrades, Lines::upgradeLabels),
                        Regions::of,
                        Traps::new,
                        () -> Invisibles.of(Lines::invisibilityLost),
                        HealPool::new,
                        PartyExit::new,
                        TeamChat::new, HiddenNames::new,
                        () -> Rooms.of(ROOMS, "bedwars", LOBBY), // before the seats open: the room is down as they do
                        () -> Staging.during(Match.OPENING).clear(LOBBY).together(Matches::together)
                                .seating((g, p) -> g.module(Kits.class).apply(p)),
                        () -> LastStanding.decides().requireSeatedAt(Match.LIVE, 2),
                        Respawns::manual,
                        KillCredit::new,
                        FriendlyFire::new,
                        () -> new Beds(BED, BEDS).warning(Lines::bedsWarning), // five minutes before the clock, wherever it stands
                        () -> new Lives(COUNTERS).onDeath(Gear::died),
                        Chests::new,
                        Compass::new,
                        Spoils::new,
                        MagicMilk::new,
                        Creatures::new,
                        () -> Limits.of().below(VOID, (p, plane) -> Matches.fell(p, (g, who) -> {
                            Lives lives = g.module(Lives.class);
                            if (lives != null) lives.fell(who);
                        })),
                        Pressure::of,
                        Nametags::of,
                        Spectating::new, Sight::new, Presets::new,
                        () -> HealthDisplay.of().inTab(g -> Matches.lookOf(g).tabHealth()),
                        () -> Kits.of(KIT).applyOnJoin(false), // the kit comes with the seat, not the room
                        () -> new Loadout().kept(Gear::kept),
                        Idle::new,
                        Rules::new, Structures::new, PopupTower::new, BridgeEgg::new, Sponge::new, Fireballs::new, TntMark::new, Presentation::new,
                        () -> Refusals.of(Lines::refusal),
                        Abandonment.endWhenDeserted(Outcome::aborted),
                        () -> Stats.of().counter(KILLS, CAREER_KILLS).counter(DEATHS, CAREER_DEATHS)
                                .counter(FINAL_KILLS, CAREER_FINAL_KILLS).counter(FINAL_DEATHS, CAREER_FINAL_DEATHS)
                                .counter(BEDS, CAREER_BEDS).wins(CAREER_WINS).losses(CAREER_LOSSES),
                        () -> Boards.hypixel(label))
                .build();
        Rulesets.check(Mechanics.polyp.profiles().global(), type);
        Mode mode = Mode.of(name, type, arena, MechanicsProfile::builder, Rulesets.BASE, Rulesets.variants())
                .title(title)
                .banner("Bed Wars") // captured: the banner's headline, whatever the mode
                .intro(INTRO.toArray(Component[]::new))
                .game()
                .ghostLoser()
                .sided()
                .look("hypixel")
                .mechanics("hypixel", "hypixel_bedwars") // the user, 2026-09-28: fire takes on contact, the pearl lands game-wide
                .tabStats(new Mode.SeatStat("Kills", KILLS), new Mode.SeatStat("Final Kills", FINAL_KILLS),
                        new Mode.SeatStat("Beds Broken", BEDS))
                .marks(Marks::render)
                .build();
        Matches.register(mode);
    }
}
