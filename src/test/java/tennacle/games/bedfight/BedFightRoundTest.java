package tennacle.games.bedfight;

import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.barnacle.Arena;
import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.bloom.Session;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.roster.Slot;
import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.enchant.Enchantment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The kit a bed fight seat gets, and the one rule the mode turns on: a bed gone makes the next death the last. */
class BedFightRoundTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    /** A deck along x, a bed behind each side, every expected marker aboard. */
    static Arena fixture() {
        Session s = Session.create("bedfight-fixture", new ShardManager(), p -> {});
        try {
            for (int x = -8; x <= 8; x++) s.instance().setBlock(x, 64, 0, Block.WHITE_WOOL);
            s.instance().setBlock(-7, 65, 0, Block.RED_BED.withProperty("facing", "east").withProperty("part", "foot"));
            s.instance().setBlock(-6, 65, 0, Block.RED_BED.withProperty("facing", "east").withProperty("part", "head"));
            s.instance().setBlock(7, 65, 0, Block.BLUE_BED.withProperty("facing", "west").withProperty("part", "foot"));
            s.instance().setBlock(6, 65, 0, Block.BLUE_BED.withProperty("facing", "west").withProperty("part", "head"));
            s.addMarker(new RegionMarker("bedfight:bed", "1", "", new BlockVec(-7, 65, 0), new BlockVec(-6, 65, 0)));
            s.addMarker(new RegionMarker("bedfight:bed", "2", "", new BlockVec(6, 65, 0), new BlockVec(7, 65, 0)));
            s.addMarker(new PosMarker("grotto:spawn", "1", "", new Pos(-4.5, 65, 0.5, -90, 0)));
            s.addMarker(new PosMarker("grotto:spawn", "2", "", new Pos(5.5, 65, 0.5, 90, 0)));
            s.addMarker(new RegionMarker("bedfight:build", "", "", new BlockVec(-8, 65, -2), new BlockVec(8, 65, 2)));
            s.addMarker(new RegionMarker("bedfight:defense", "1", "", new BlockVec(-8, 64, -1), new BlockVec(-5, 65, 1)));
            s.addMarker(new RegionMarker("bedfight:defense", "2", "", new BlockVec(5, 64, -1), new BlockVec(8, 65, 1)));
            s.addMarker(new LimitMarker("bedfight:height", "pinned", "", null, 72.0, null));
            s.addMarker(new LimitMarker("bedfight:floor", "pinned", "", null, 60.0, null));
            s.addMarker(new LimitMarker("bedfight:void", "", "", null, 54.0, null));
            return Arena.read(s.save());
        } finally {
            s.close();
        }
    }

    @Test
    void theKitIsTheTeamsOwn() {
        Mode bedfight = Matches.mode("bedfight");
        assertNotNull(bedfight);
        Game game = Matches.launch(bedfight, Matches.platformFor(null), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BedKitRed");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            await("seated", () -> Game.of(red.player) == game);

            ItemStack helmet = red.player.getEquipment(EquipmentSlot.HELMET);
            assertEquals(Material.LEATHER_HELMET, helmet.material());
            assertNotNull(helmet.get(DataComponents.DYED_COLOR), "dyed to the side");
            assertEquals(1, helmet.get(DataComponents.ENCHANTMENTS).level(Enchantment.AQUA_AFFINITY),
                    "the helmet alone carries aqua affinity");
            assertEquals(Material.LEATHER_CHESTPLATE, red.player.getEquipment(EquipmentSlot.CHESTPLATE).material());
            assertEquals(Material.LEATHER_LEGGINGS, red.player.getEquipment(EquipmentSlot.LEGGINGS).material());
            assertEquals(Material.LEATHER_BOOTS, red.player.getEquipment(EquipmentSlot.BOOTS).material());

            var bag = red.player.getInventory();
            assertEquals(Material.WOODEN_SWORD, bag.getItemStack(0).material());
            assertEquals(1, bag.getItemStack(1).get(DataComponents.ENCHANTMENTS).level(Enchantment.EFFICIENCY),
                    "the pickaxe digs faster");
            assertEquals(Material.WOODEN_AXE, bag.getItemStack(2).material());
            assertEquals(Material.SHEARS, bag.getItemStack(3).material());
            ItemStack wool = bag.getItemStack(4);
            assertEquals(64, wool.amount(), "a stack of it");
            assertTrue(wool.material().name().endsWith("_wool"), "in the side's own color: " + wool.material().name());
        } finally {
            game.end(Outcome.aborted());
            red.player.remove();
        }
    }

    @Test
    void aBrokenBedMakesTheNextDeathTheLast() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor(null), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BedRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "BedBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            Slot seat = game.roster().slotOf(red.player);
            assertNotNull(seat);
            String reds = seat.team();
            String blues = Match.teamOf(game, blue.player);
            assertTrue(Beds.stands(game, reds) && Beds.stands(game, blues), "both beds stand at the start");
            live(game); // the freeze refuses every break until the countdown runs out

            // red's own bed: never a target
            break0(red.player, new BlockVec(-7, 65, 0));
            assertTrue(Beds.stands(game, reds), "nobody breaks their own");

            break0(blue.player, new BlockVec(-7, 65, 0));
            assertFalse(Beds.stands(game, reds), "the enemy took it");
            assertTrue(Beds.stands(game, blues), "theirs still stands");
            assertEquals(Block.AIR, game.shard().getBlock(new BlockVec(-6, 65, 0)), "both halves go");
            assertEquals(1, game.state().get(io.github.term4.grotto.state.Scope.slot(
                    game.roster().slotOf(blue.player).index()), BedFight.BEDS), "the breaker is credited");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    /** The fixture's build box is flat at y=65 with the band 60..72: the whole column over it builds. */
    @Test
    void flatBuildBoxesAreSlices() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor(null), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BedBuilder");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "BedBuilderFoe"); // the countdown waits for both seats
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            tennacle.Mechanics.polyp.clientInfo().setProtocol(red.player, 47); // the band decides, not a modern reach
            live(game);

            assertFalse(refused(red, game, 0, 68, 1), "over the slice, inside the band");
            assertTrue(refused(red, game, 0, 75, 1), "over the height");
            assertTrue(refused(red, game, 0, 58, 1), "under the floor");
            assertTrue(refused(red, game, 20, 68, 1), "off the slice's footprint");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    private static boolean refused(FakePlayer p, Game game, int x, int y, int z) {
        var event = new net.minestom.server.event.player.PlayerBlockPlaceEvent(p.player, game.instance(),
                Block.WHITE_WOOL, net.minestom.server.instance.block.BlockFace.TOP, new BlockVec(x, y, z),
                new Pos(0.5, 1, 0.5), net.minestom.server.entity.PlayerHand.MAIN);
        net.minestom.server.event.EventDispatcher.call(event);
        return event.isCancelled();
    }

    /** The clock takes every bed at once, and a bedless side's board row counts down to the cross. */
    @Test
    void theClockTakesEveryBed() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor(null), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BedClockRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "BedClockBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            live(game);
            assertTrue(Beds.fallsAt(game) > 0, "the clock starts with the live phase");

            Matches.modify(game, tennacle.games.Ruleset.named("soon").rule(Beds.DESTROY, 0L));
            tickUntil(game, "the beds fell", () -> Beds.standing(game).isEmpty());
            assertEquals(Block.AIR, game.shard().getBlock(new BlockVec(-7, 65, 0)), "both sides' blocks go");
            assertEquals(Block.AIR, game.shard().getBlock(new BlockVec(7, 65, 0)));
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    /** Only a bed's defenses come apart: the map stands everywhere else, a seat's own blocks come back up. */
    @Test
    void onlyDefensesComeApart() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor(null), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BedDefenseRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "BedDefenseBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            tennacle.Mechanics.polyp.clientInfo().setProtocol(red.player, 47);
            live(game);

            assertFalse(refusedBreak(red, game, -6, 64, 0), "the wool defending a bed comes off");
            assertTrue(refusedBreak(red, game, 0, 64, 0), "the deck between them does not");

            // a block the seat placed itself is theirs to take back, defended or not
            var placed = new net.minestom.server.event.player.PlayerBlockPlaceEvent(red.player, game.instance(),
                    Block.WHITE_WOOL, net.minestom.server.instance.block.BlockFace.TOP, new BlockVec(0, 66, 1),
                    new Pos(0.5, 1, 0.5), net.minestom.server.entity.PlayerHand.MAIN);
            net.minestom.server.event.EventDispatcher.call(placed);
            assertFalse(placed.isCancelled(), "the placement landed");
            assertFalse(refusedBreak(red, game, 0, 66, 1), "and comes back up");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    private static boolean refusedBreak(FakePlayer p, Game game, int x, int y, int z) {
        var event = new PlayerBlockBreakEvent(p.player, game.instance(), Block.WHITE_WOOL, Block.AIR,
                new BlockVec(x, y, z), net.minestom.server.instance.block.BlockFace.TOP);
        net.minestom.server.event.EventDispatcher.call(event);
        return event.isCancelled();
    }

    /** A preset brings the whole platform: its mechanics, its look, and the rules its capture showed. */
    @Test
    void aPresetBringsTheWholePlatform() {
        Mode bedfight = Matches.mode("bedfight");
        Game house = Matches.launch(bedfight, Matches.platformFor(null), fixture());
        Game hypixel = Matches.launch(bedfight, Matches.platformFor("hypixel"), fixture());
        try {
            assertEquals("hypixel", hypixel.rules().resolve(io.github.term4.grotto.state.Scope.GAME,
                    tennacle.games.Presets.KEY), "the mechanics are the platform's");
            assertEquals("tennacle", Matches.lookNameOf(house), "no platform: the house look");
            assertEquals("hypixel", Matches.lookNameOf(hypixel), "and the platform's own where there is one");
            assertEquals(5L, (long) hypixel.rules().resolve(io.github.term4.grotto.state.Scope.GAME,
                            io.github.term4.grotto.modules.flow.Countdown.FROM),
                    "captured: hypixel opens on five");
            assertEquals(3L, (long) house.rules().resolve(io.github.term4.grotto.state.Scope.GAME,
                            io.github.term4.grotto.modules.flow.Countdown.FROM),
                    "the mode's own everywhere else");
        } finally {
            house.end(Outcome.aborted());
            hypixel.end(Outcome.aborted());
        }
    }

    // the countdown holds every break until GO
    /** The count is on screen while they wait, and off it by the time the body is back. */
    @Test
    void theDeathScreenGoesFirst() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor(null), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BedDeadRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "BedDeadBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            live(game);

            Respawns respawns = game.module(Respawns.class);
            assertNotNull(respawns);
            red.sent.clear();
            respawns.queue(red.player);
            assertTrue(respawns.isDead(red.player), "the wait is on");
            assertTrue(counts(red).stream().anyMatch(t -> t.contains("Respawning in")), "the count shows: " + counts(red));

            tickUntil(game, "the respawn lands", () -> !respawns.isDead(red.player));
            List<String> shown = titles(red);
            assertEquals("", shown.getLast(), "the last word to the client is the wipe: " + shown);
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    /** The row stays through the wait, greyed for everyone, and comes back in the side's color. */
    @Test
    void aDeadRowGreysAndComesBack() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor(null), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BedTagRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "BedTagBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            live(game);
            Slot seat = game.roster().slotOf(red.player);
            assertNotNull(seat);
            net.kyori.adventure.text.format.TextColor side = game.roster().team(seat.team()).color();
            assertEquals(side, color(red.player), "seated in the side's color");

            Respawns respawns = game.module(Respawns.class);
            assertNotNull(respawns);
            respawns.queue(red.player);
            assertEquals(net.kyori.adventure.text.format.NamedTextColor.GRAY, color(red.player), "greyed while down");
            assertTrue(io.github.term4.archipelago.shard.ShardTabList.listed(blue.player, red.player),
                    "and the enemy still lists them");

            tickUntil(game, "the respawn lands", () -> !respawns.isDead(red.player));
            assertEquals(side, color(red.player), "the side's color is back");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    private static net.kyori.adventure.text.format.TextColor color(net.minestom.server.entity.Player p) {
        net.kyori.adventure.text.Component name = p.getDisplayName();
        return name != null ? name.color() : null;
    }

    /** -preset hypixel dresses the game, not just its mechanics: the captured board and the captured bed line. */
    @Test
    void aHypixelPresetWearsItsLook() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor("hypixel"), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BedLookRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "BedLookBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            assertEquals("hypixel", Matches.lookNameOf(game), "the preset's own look, not the house one");
            live(game);

            io.github.term4.canvas.board.Boards.Rendered board = io.github.term4.canvas.board.Boards.rendered(red.player);
            assertNotNull(board, "the board is up");
            List<String> rows = board.lines().stream().map(BedFightRoundTest::plain).toList();
            assertEquals("BED WARS", plain(board.title()), "the captured title");
            assertTrue(rows.stream().anyMatch(l -> l.startsWith("Time Left: ")), "the captured clock row: " + rows);
            assertTrue(rows.stream().anyMatch(l -> l.contains("R Red: ")), "a line a side: " + rows);
            assertTrue(rows.stream().anyMatch(l -> l.strip().equals("Kills: 0")), "the viewer's own kills: " + rows);
            assertTrue(rows.stream().anyMatch(l -> l.strip().equals("Mode: Bed Rush Duel")), "the captured mode row: " + rows);

            Slot mine = game.roster().slotOf(red.player);
            assertNotNull(mine);
            String side = tennacle.looks.Palette.titled(game.roster().team(mine.team()));
            red.sent.clear();
            break0(blue.player, new BlockVec(-7, 65, 0)); // the fixture's bed for red's own side
            assertTrue(said(red).stream().anyMatch(
                            l -> l.equals("BED DESTRUCTION > " + side + " Bed was destroyed by BedLookBlue!")),
                    "the captured bed line: " + said(red));
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    /** Taking the enemy bed is no refusal, the footprint it leaves is ordinary ground, and your own is safe. */
    @Test
    void breakingABedIsNotRefused() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor(null), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BedFreeRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "BedFreeBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            live(game);
            BlockVec bed = new BlockVec(-7, 65, 0);

            red.sent.clear();
            assertTrue(breakAt(red.player, game, Block.RED_BED, bed).isCancelled(), "your own bed stands");
            assertTrue(turnedAway(red), "and being turned away says so");

            blue.sent.clear();
            assertTrue(breakAt(blue.player, game, Block.RED_BED, bed).isCancelled(),
                    "the whole bed goes at once, so the event does not");
            assertFalse(turnedAway(blue), "but taking it is no refusal");
            assertEquals(Block.AIR, game.shard().getBlock(bed), "the bed is gone");

            blue.player.teleport(new Pos(-5.5, 65, 0.5)).join(); // within reach of the footprint
            assertFalse(refused(blue, game, bed.blockX(), bed.blockY(), bed.blockZ()),
                    "the footprint builds like any other ground");
            game.shard().setBlock(bed, Block.WHITE_WOOL);
            assertFalse(breakAt(blue.player, game, Block.WHITE_WOOL, bed).isCancelled(), "and comes back up");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    /** One line a death, the look's own, with the captured note on the last. */
    @Test
    void aFinalKillReadsOnce() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor("hypixel"), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BedLineRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "BedLineBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            live(game);
            Slot mine = game.roster().slotOf(red.player);
            assertNotNull(mine);

            blue.sent.clear();
            game.module(Rules.class).fell(red.player);
            List<String> ordinary = said(blue).stream().filter(l -> l.contains("BedLineRed")).toList();
            assertEquals(List.of("BedLineRed fell into the void."), ordinary, "one line, the look's: " + said(blue));

            break0(blue.player, new BlockVec(-7, 65, 0));
            assertFalse(Beds.stands(game, mine.team()), "their bed is gone");
            blue.sent.clear();
            game.module(Rules.class).fell(red.player);
            List<String> last = said(blue).stream().filter(l -> l.contains("BedLineRed")).toList();
            assertEquals(List.of("BedLineRed fell into the void. FINAL KILL!"), last, "and one on the last: " + said(blue));
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    // Refusals speaks at the end of the tick, whoever cancelled - unless the canceller claimed the action
    private static boolean turnedAway(FakePlayer p) {
        MinecraftServer.getSchedulerManager().processTick();
        MinecraftServer.getSchedulerManager().processTickEnd();
        return said(p).stream().anyMatch(l -> l.contains("can't break this block"));
    }

    private static PlayerBlockBreakEvent breakAt(net.minestom.server.entity.Player who, Game game, Block block, BlockVec at) {
        var event = new PlayerBlockBreakEvent(who, game.instance(), block, Block.AIR, at,
                net.minestom.server.instance.block.BlockFace.TOP);
        net.minestom.server.event.EventDispatcher.call(event);
        return event;
    }

    private static List<String> said(FakePlayer p) {
        return p.sent(net.minestom.server.network.packet.server.play.SystemChatPacket.class).stream()
                .map(c -> plain(c.message())).toList();
    }

    private static List<String> titles(FakePlayer p) {
        return p.sent(net.minestom.server.network.packet.server.play.SetTitleTextPacket.class).stream()
                .map(t -> plain(t.title())).toList();
    }

    private static List<String> counts(FakePlayer p) {
        return p.sent(net.minestom.server.network.packet.server.play.SetTitleSubTitlePacket.class).stream()
                .map(t -> plain(t.subtitle())).toList();
    }

    private static String plain(net.kyori.adventure.text.Component c) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static void live(Game game) {
        tickUntil(game, "the countdown ran out", () -> Match.live(game));
    }

    private static void tickUntil(Game game, String what, java.util.function.Supplier<Boolean> done) {
        long deadline = System.currentTimeMillis() + 8000;
        while (!done.get() && System.currentTimeMillis() < deadline) {
            MinecraftServer.getSchedulerManager().processTick();
            MinecraftServer.getSchedulerManager().processTickEnd();
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertTrue(done.get(), what);
    }

    private static void break0(net.minestom.server.entity.Player breaker, BlockVec at) {
        net.minestom.server.event.EventDispatcher.call(new PlayerBlockBreakEvent(breaker, breaker.getInstance(),
                Block.RED_BED, Block.AIR, at, net.minestom.server.instance.block.BlockFace.TOP));
    }
}
