package tennacle.games.bedfight;

import net.minestom.server.potion.PotionEffect;
import tennacle.games.beds.Beds;
import tennacle.games.lives.Lives;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.watch.Spectators;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.barnacle.Arena;
import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.bloom.mapmaking.Session;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.modules.outcome.LastStanding;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.network.packet.server.play.SoundEffectPacket;
import net.minestom.server.sound.SoundEvent;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The kit a bed fight seat gets, and the one rule the mode turns on: a bed gone makes the next death the last. */
class BedFightRoundTest extends HeadlessServerTest {

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path replays;

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        tennacle.glue.ReplayBridge.install(); // the bookmarks ride a running recording
        tennacle.games.watch.Replays.install(replays, new ShardManager());
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
    void aMissingSideAbortsAtGo() {
        Game game = Matches.launch(Matches.mode("bedfight"), Matches.platformFor(null), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "LoneRed");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            tickUntil(game, "the countdown ran out", () -> game.status() != Game.Status.OPEN || game.decided() != null);
            assertEquals(Outcome.aborted(), game.status() == Game.Status.OPEN ? game.decided() : game.outcome(),
                    "blue never arrived");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
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

            red.sent.clear();
            blue.sent.clear();
            break0(blue.player, new BlockVec(-7, 65, 0));
            assertFalse(Beds.stands(game, reds), "the enemy took it");
            assertBedSound(red, SoundEvent.ENTITY_WITHER_DEATH, "the side that lost it");
            assertBedSound(blue, SoundEvent.ENTITY_ENDER_DRAGON_GROWL, "everyone else");
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
            tennacle.boot.Mechanics.polyp.clientInfo().setProtocol(red.player, 47); // the band decides, not a modern reach
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
            tennacle.boot.Mechanics.polyp.clientInfo().setProtocol(red.player, 47);
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
            assertEquals(100L, hypixel.rules().resolve(io.github.term4.grotto.state.Scope.GAME,
                    io.github.term4.grotto.modules.combat.Respawns.RESPAWN_TICKS), "hypixel waits five seconds");
            assertEquals(60L, house.rules().resolve(io.github.term4.grotto.state.Scope.GAME,
                    io.github.term4.grotto.modules.combat.Respawns.RESPAWN_TICKS), "the mode's own three");
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

    /** The row stays through the wait, grayed for everyone, and comes back in the side's color. */
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
            assertEquals(net.kyori.adventure.text.format.NamedTextColor.GRAY, color(red.player), "grayed while down");
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
            game.module(Lives.class).fell(red.player);
            List<String> ordinary = said(blue).stream().filter(l -> l.contains("BedLineRed")).toList();
            assertEquals(List.of("BedLineRed fell into the void."), ordinary, "one line, the look's: " + said(blue));
            assertFalse(red.player.hasEffect(PotionEffect.INVISIBILITY) || red.player.hasEffect(PotionEffect.NIGHT_VISION),
                    "a duel's dead wear no potion, Bed Wars' do");

            break0(blue.player, new BlockVec(-7, 65, 0));
            assertFalse(Beds.stands(game, mine.team()), "their bed is gone");
            game.module(Respawns.class).respawn(red.player); // back on their feet: a waiting ghost cannot die again
            blue.sent.clear();
            game.module(Lives.class).fell(red.player);
            List<String> last = said(blue).stream().filter(l -> l.contains("BedLineRed")).toList();
            assertEquals(List.of("BedLineRed fell into the void. FINAL KILL!"), last, "and one on the last: " + said(blue));
            String side = tennacle.looks.Palette.titled(game.roster().team(mine.team()));
            assertTrue(said(blue).stream().anyMatch(l -> l.equals("TEAM ELIMINATED > " + side + " Team has been eliminated!")),
                    "and the side is called out: " + said(blue));
            assertEquals("TEAM ELIMINATED > ", boldOf(banner(blue, "TEAM ELIMINATED")),
                    "the headline alone is bold");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    /** The user, 2026-09-29: the house prints Hypixel's bed lines, headline and all. */
    @Test
    void theHouseWearsHypixelsLines() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor(null), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "HouseBedRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "HouseBedBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            assertEquals("tennacle", Matches.lookNameOf(game), "the house look");
            live(game);
            Slot mine = game.roster().slotOf(red.player);
            assertNotNull(mine);
            String side = tennacle.looks.Palette.titled(game.roster().team(mine.team()));

            // a side game names the speaker's side, the way bridge does - no team score to gate it on
            var chat = new net.minestom.server.event.player.PlayerChatEvent(
                    blue.player, List.of(red.player, blue.player), "gg");
            net.minestom.server.event.EventDispatcher.call(chat);
            assertEquals("[" + tennacle.games.Match.teamName(game, blue.player) + "] HouseBedBlue: gg",
                    plain(chat.getFormattedMessage()), "the side rides the line, as it does in bridge");

            blue.sent.clear();
            break0(blue.player, new BlockVec(-7, 65, 0));
            assertTrue(said(blue).stream().anyMatch(l -> l.equals("BED DESTRUCTION > " + side + " Bed was destroyed by HouseBedBlue!")),
                    "Hypixel's bed line: " + said(blue));

            blue.sent.clear();
            game.module(Lives.class).fell(red.player);
            assertEquals(List.of("HouseBedRed fell into the void. FINAL KILL!"),
                    said(blue).stream().filter(l -> l.contains("HouseBedRed")).toList(),
                    "one line, with the note: " + said(blue));
            assertTrue(said(blue).stream().anyMatch(l -> l.equals("TEAM ELIMINATED > " + side + " Team has been eliminated!")),
                    "and Hypixel's elimination: " + said(blue));
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    /** Minemen speaks its own: a guillemet headline, yellow prose, and an elimination that names the player. */
    @Test
    void minemenSpeaksItsOwnLines() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor("minemen"), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "MmcBedRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "MmcBedBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            assertEquals("minemen", Matches.lookNameOf(game));
            live(game);
            Slot mine = game.roster().slotOf(red.player);
            assertNotNull(mine);
            String side = tennacle.looks.Palette.titled(game.roster().team(mine.team()));

            blue.sent.clear();
            break0(blue.player, new BlockVec(-7, 65, 0));
            assertTrue(said(blue).stream().anyMatch(
                            l -> l.equals("BED DESTRUCTION » " + side + " Bed was destroyed by MmcBedBlue!")),
                    "the captured minemen headline: " + said(blue));
            assertEquals("BED DESTRUCTION »", boldOf(banner(blue, "BED DESTRUCTION")),
                    "the headline and its guillemet are bold, the rest is not");

            blue.sent.clear();
            game.module(Lives.class).fell(red.player);
            assertEquals(List.of("MmcBedRed fell into the void! FINAL KILL"),
                    said(blue).stream().filter(l -> l.contains("MmcBedRed fell")).toList(),
                    "minemen shouts no exclamation on the note: " + said(blue));
            assertTrue(said(blue).stream().noneMatch(l -> l.contains("ELIMINAT")),
                    "minemen names no elimination in bed fight: " + said(blue));
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    /** A tint region retints like a section and counts for nothing: any number, any shape, no geometry. */
    @Test
    void aTintRegionPaintsWithoutCounting() {
        Session s = Session.create("bedfight-tinted", new ShardManager(), p -> {});
        Arena arena;
        try {
            for (int x = -8; x <= 8; x++) s.instance().setBlock(x, 64, 0, Block.WHITE_WOOL);
            s.instance().setBlock(-7, 65, 0, Block.RED_BED.withProperty("facing", "east").withProperty("part", "foot"));
            s.instance().setBlock(-6, 65, 0, Block.RED_BED.withProperty("facing", "east").withProperty("part", "head"));
            s.instance().setBlock(7, 65, 0, Block.BLUE_BED.withProperty("facing", "west").withProperty("part", "foot"));
            s.instance().setBlock(6, 65, 0, Block.BLUE_BED.withProperty("facing", "west").withProperty("part", "head"));
            s.instance().setBlock(0, 70, 5, Block.WHITE_WOOL); // a banner far off the deck, dyed but not territory
            s.instance().setBlock(0, 73, 5, Block.WHITE_WOOL); // a second area, wearing a role of its own
            s.addMarker(new RegionMarker("bedfight:bed", "1", "", new BlockVec(-7, 65, 0), new BlockVec(-6, 65, 0)));
            s.addMarker(new RegionMarker("bedfight:bed", "2", "", new BlockVec(6, 65, 0), new BlockVec(7, 65, 0)));
            s.addMarker(new PosMarker("grotto:spawn", "1", "", new Pos(-4.5, 65, 0.5, -90, 0)));
            s.addMarker(new PosMarker("grotto:spawn", "2", "", new Pos(5.5, 65, 0.5, 90, 0)));
            s.addMarker(new RegionMarker("bedfight:build", "", "", new BlockVec(-8, 65, -2), new BlockVec(8, 65, 2)));
            s.addMarker(new RegionMarker("bedfight:defense", "1", "", new BlockVec(-8, 64, -1), new BlockVec(-5, 65, 1)));
            s.addMarker(new RegionMarker("bedfight:defense", "2", "", new BlockVec(5, 64, -1), new BlockVec(8, 65, 1)));
            // three of them, where a mode expecting a count would already have thrown
            s.addMarker(new RegionMarker("tint", "1", "", new BlockVec(0, 70, 5), new BlockVec(0, 70, 5)));
            s.addMarker(new RegionMarker("tint", "1", "", new BlockVec(0, 71, 5), new BlockVec(0, 71, 5)));
            s.addMarker(new RegionMarker("tint", "2", "", new BlockVec(0, 72, 5), new BlockVec(0, 72, 5)));
            // a role of its own: unnamed by this look, so it falls back to the side's base rather than failing
            s.addMarker(new RegionMarker("tint", "1:trim", "", new BlockVec(0, 73, 5), new BlockVec(0, 73, 5)));
            s.addMarker(new LimitMarker("bedfight:height", "pinned", "", null, 72.0, null));
            s.addMarker(new LimitMarker("bedfight:floor", "pinned", "", null, 60.0, null));
            s.addMarker(new LimitMarker("bedfight:void", "", "", null, 54.0, null));
            arena = Arena.read(s.save());
        } finally {
            s.close();
        }

        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor(null), arena); // no throw: nothing counts tint
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "TintRed");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            await("seated", () -> Game.of(red.player) == game);
            tickUntil(game, "the stamp lands", () -> game.shard().getBlock(0, 70, 5) != Block.WHITE_WOOL);
            assertNotEquals(Block.WHITE_WOOL, game.shard().getBlock(0, 70, 5), "the tint region repainted its cell");
            assertNotEquals(Block.WHITE_WOOL, game.shard().getBlock(0, 73, 5),
                    "a role of its own paints too - base is every role's last fallback");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
        }
    }

    /** A watcher arriving mid-game reads the health already on the board, not the zero of a score never sent. */
    @Test
    void aWatcherSeesTheHealthUnderEveryHead() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor(null), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "HpSeenRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "HpSeenBlue");
        FakePlayer watcher = FakePlayer.connect(instance, new Pos(4.5, 65, 0.5), "HpWatcher");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            live(game);
            red.player.setHealth(14f);
            tickUntil(game, "the board took their health", () -> true);
            MinecraftServer.getSchedulerManager().processTick();
            MinecraftServer.getSchedulerManager().processTickEnd();

            watcher.sent.clear();
            Spectators.enter(game, watcher.player, red.player);
            tickUntil(game, "the view lands", () -> io.github.term4.archipelago.shard.ShardView.of(watcher.player)
                    .blocks() == game.shard());

            List<net.minestom.server.network.packet.server.play.UpdateScorePacket> scores =
                    watcher.sent(net.minestom.server.network.packet.server.play.UpdateScorePacket.class);
            assertTrue(scores.stream().anyMatch(p -> p.entityName().equals("HpSeenRed") && p.score() == 14),
                    "the watcher was told what is over their head: " + scores);
        } finally {
            Spectators.exit(watcher.player);
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
            watcher.player.remove();
        }
    }

    /** Walking out is a death; the bed lost while away spends it, and a death taken here keeps its respawn. */
    @Test
    void leavingOwesARespawnTheBedCanSpend() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor(null), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "AwayRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "AwayBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            live(game);
            Slot seat = game.roster().slotOf(red.player);
            assertNotNull(seat);
            Scope mine = Scope.slot(seat.index());
            LastStanding standing = game.module(LastStanding.class);
            assertNotNull(standing);

            // a death taken HERE keeps its respawn even when the bed falls mid-wait
            Respawns respawns = game.module(Respawns.class);
            respawns.queue(red.player);
            assertTrue(respawns.isDead(red.player), "waiting on a respawn");
            break0(blue.player, new BlockVec(-7, 65, 0));
            assertFalse(Beds.stands(game, seat.team()), "their bed went while they waited");
            tickUntil(game, "the respawn still lands", () -> !respawns.isDead(red.player));
            assertFalse(game.state().get(mine, LastStanding.OUT), "the queue keeps what it promised");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    /** With the bed still up, leaving owes a respawn - and losing the bed while away is the end of it. */
    @Test
    void aBedLostWhileAwayEndsTheSeat() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor(null), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "GoneRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "GoneBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            live(game);
            Slot seat = game.roster().slotOf(red.player);
            assertNotNull(seat);
            Scope mine = Scope.slot(seat.index());
            int died = game.state().get(mine, BedFight.DEATHS);

            game.depart(red.player); // the seat is held for them, as a disconnect holds it
            assertEquals(died + 1, game.state().get(mine, BedFight.DEATHS), "walking out is a death");
            assertFalse(game.state().get(mine, LastStanding.OUT), "but the bed still stands, so they may come back");

            break0(blue.player, new BlockVec(-7, 65, 0));
            assertTrue(game.state().get(mine, LastStanding.OUT), "the bed went while they were away: that was the last");
            assertEquals(1, game.state().get(mine, BedFight.FINAL_DEATHS), "a final death for the one who left");
            Slot theirs = game.roster().slotOf(blue.player);
            assertNotNull(theirs);
            assertEquals(1, game.state().get(Scope.slot(theirs.index()), BedFight.FINAL_KILLS), "and the breaker's final kill");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    /** The user, 2026-10-01: a final death nobody hit counts for the bed breaker, the line as it was. */
    @Test
    void aLoneFallPaysTheBreaker() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor("hypixel"), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "LoneRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "LoneBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            live(game);
            Slot theirs = game.roster().slotOf(blue.player);
            assertNotNull(theirs);
            Scope breaker = Scope.slot(theirs.index());

            break0(blue.player, new BlockVec(-7, 65, 0));
            blue.sent.clear();
            game.module(Lives.class).fell(red.player);
            assertEquals(List.of("LoneRed fell into the void. FINAL KILL!"),
                    said(blue).stream().filter(l -> l.contains("LoneRed")).toList(), "nobody named: " + said(blue));
            assertEquals(1, game.state().get(breaker, BedFight.FINAL_KILLS), "the breaker's final kill");
            assertEquals(1, game.state().get(breaker, BedFight.KILLS), "and kill");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    /** The breaker waiting to respawn is owed nothing. */
    @Test
    void aDeadBreakerIsOwedNothing() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor("hypixel"), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "OwedRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "OwedBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            live(game);
            Slot theirs = game.roster().slotOf(blue.player);
            assertNotNull(theirs);

            break0(blue.player, new BlockVec(-7, 65, 0));
            game.module(Lives.class).fell(blue.player); // their own bed stands: they wait to respawn
            assertTrue(game.module(Respawns.class).isDead(blue.player));
            game.module(Lives.class).fell(red.player);
            assertEquals(0, game.state().get(Scope.slot(theirs.index()), BedFight.FINAL_KILLS), "dead, owed nothing");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    /** Walking out inside the credit window is a kill for whoever last landed a hit, and a death for the leaver. */
    @Test
    void aHitThenALeaveIsAKill() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor(null), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "LogRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "LogBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            live(game);
            Slot mine = game.roster().slotOf(red.player);
            Slot theirs = game.roster().slotOf(blue.player);
            assertNotNull(mine);
            assertNotNull(theirs);

            io.github.term4.grotto.modules.combat.KillCredit credit =
                    game.module(io.github.term4.grotto.modules.combat.KillCredit.class);
            assertNotNull(credit);
            credit.record(red.player, blue.player, game.state().tick()); // blue got a hit in

            game.depart(red.player);
            assertEquals(1, game.state().get(Scope.slot(mine.index()), BedFight.DEATHS), "walking out is a death");
            assertEquals(1, game.state().get(Scope.slot(theirs.index()), BedFight.KILLS),
                    "and the hit inside the window takes the kill with it");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    /** Coming back owes a wait of its own, longer than a death's: the rejoin is priced by its own rule. */
    @Test
    void aRejoinWaitsItsOwnClock() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor(null), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BackRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "BackBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            live(game);
            Slot seat = game.roster().slotOf(red.player);
            assertNotNull(seat);
            Scope mine = Scope.slot(seat.index());

            game.depart(red.player);
            assertFalse(game.state().get(mine, io.github.term4.grotto.modules.combat.Respawns.DEAD),
                    "nobody waits in an empty seat");

            assertTrue(game.join(red.player), "the seat was held for them"); // still standing in the world they left
            tickUntil(game, "the wait starts a tick on, over every join hook", () -> game.isGhost(red.player));
            assertTrue(game.state().get(mine, io.github.term4.grotto.modules.combat.Respawns.DEAD),
                    "and they come back into the wait, not onto the map");
            assertTrue(game.isGhost(red.player), "a ghost until it runs out - no member, so Game.of reads null");

            io.github.term4.grotto.modules.combat.Respawns respawns =
                    game.module(io.github.term4.grotto.modules.combat.Respawns.class);
            assertNotNull(respawns);
            assertEquals(120L, respawns.remaining(red.player), "the rejoin's own clock, not the death's 60");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    /** Out of the seat is out of the seat: the countdown leaver never reached the handler that once recorded it. */
    @Test
    void aCountdownLeaverIsOutToo() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor(null), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "EarlyRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "EarlyBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            Slot seat = game.roster().slotOf(red.player);
            assertNotNull(seat);
            Scope mine = Scope.slot(seat.index());

            game.depart(red.player); // before the cage opens, so no death is owed and nothing records them
            live(game);
            assertFalse(game.state().get(mine, LastStanding.OUT), "the bed still stands, so they may come back");

            break0(blue.player, new BlockVec(-7, 65, 0));
            assertTrue(game.state().get(mine, LastStanding.OUT), "nobody was sitting there when it went");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    /** The replay keeps the bed that went and the death nobody came back from, both sides of each named. */
    @Test
    void theReplayKeepsBedsAndFinalKills() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor(null), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "MarkRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "MarkBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            live(game);
            Slot seat = game.roster().slotOf(red.player);
            assertNotNull(seat);

            List<String> marked = new java.util.ArrayList<>();
            break0(blue.player, new BlockVec(-7, 65, 0));
            game.module(Lives.class).fell(red.player);
            for (var mark : tennacle.games.watch.Replays.marksOf(game)) {
                marked.add(mark.name());
                if (mark.name().equals("bed")) {
                    assertEquals(seat.team(), mark.data().getString("bedTeam"), "whose bed it was");
                    assertEquals("MarkBlue", mark.data().getString("breaker"), "and who took it");
                    assertFalse(mark.data().getString("breakerTeam").isEmpty(), "with the side they play for");
                }
                if (mark.name().equals("final")) {
                    assertEquals("MarkRed", mark.data().getString("victim"), "who went out");
                    assertEquals(seat.team(), mark.data().getString("victimTeam"), "and their side");
                }
            }
            assertTrue(marked.contains("bed"), "the bed is bookmarked: " + marked);
            assertTrue(marked.contains("final"), "and so is the last death: " + marked);
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

    /** The chat message carrying {@code text}, as the client would read it. */
    private static net.kyori.adventure.text.Component banner(FakePlayer p, String text) {
        return p.sent(net.minestom.server.network.packet.server.play.SystemChatPacket.class).stream()
                .map(net.minestom.server.network.packet.server.play.SystemChatPacket::message)
                .filter(c -> plain(c).contains(text)).findFirst().orElseThrow();
    }

    /** Every run the client would draw bold, joined - style inherits, so this walks the tree the way it renders. */
    private static String boldOf(net.kyori.adventure.text.Component root) {
        StringBuilder out = new StringBuilder();
        walkBold(root, net.kyori.adventure.text.format.Style.empty(), out);
        return out.toString();
    }

    private static void walkBold(net.kyori.adventure.text.Component c, net.kyori.adventure.text.format.Style from,
                                 StringBuilder out) {
        net.kyori.adventure.text.format.Style mine = from.merge(c.style(),
                net.kyori.adventure.text.format.Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (mine.hasDecoration(net.kyori.adventure.text.format.TextDecoration.BOLD)
                && c instanceof net.kyori.adventure.text.TextComponent t) {
            out.append(t.content());
        }
        for (net.kyori.adventure.text.Component child : c.children()) walkBold(child, mine, out);
    }

    private static List<String> raw(FakePlayer p) {
        return p.sent(net.minestom.server.network.packet.server.play.SystemChatPacket.class).stream()
                .map(c -> plain(c.message())).toList();
    }

    private static List<String> said(FakePlayer p) {
        return p.sent(net.minestom.server.network.packet.server.play.SystemChatPacket.class).stream()
                .map(c -> plain(c.message()).strip()) // a banner rides a blank line either side
                .toList();
    }

    /** A watcher took no side, so the end names none: gold, and not bold. */
    @Test
    void aWatcherReadsGameOver() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor(null), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "OverRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "OverBlue");
        FakePlayer watcher = FakePlayer.connect(instance, new Pos(4.5, 65, 0.5), "OverWatch");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            live(game);
            tennacle.games.watch.Spectators.enter(game, watcher.player, red.player);
            await("watching", () -> game.spectators().contains(watcher.player));

            red.sent.clear();
            watcher.sent.clear();
            game.decide(Outcome.winner(game.roster().slotOf(red.player).team()), 1);

            assertTrue(titles(red).stream().anyMatch(t -> t.contains("Victory")), "a seat reads its own: " + titles(red));
            assertEquals(List.of("Game Over!"), titles(watcher), "and a watcher reads no side's");
            net.kyori.adventure.text.Component over = watcher.sent(net.minestom.server.network.packet.server.play.SetTitleTextPacket.class)
                    .getFirst().title();
            assertEquals(net.kyori.adventure.text.format.NamedTextColor.GOLD, over.color());
            assertNotEquals(net.kyori.adventure.text.format.TextDecoration.State.TRUE,
                    over.decoration(net.kyori.adventure.text.format.TextDecoration.BOLD), "plain, not bold");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
            watcher.player.remove();
        }
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

    // from the broken block's center, as both networks send it
    private static void assertBedSound(FakePlayer p, SoundEvent expected, String who) {
        List<SoundEffectPacket> bed = p.sent(SoundEffectPacket.class).stream()
                .filter(s -> SoundEvent.ENTITY_WITHER_DEATH.equals(s.soundEvent())
                        || SoundEvent.ENTITY_ENDER_DRAGON_GROWL.equals(s.soundEvent()))
                .toList();
        assertEquals(1, bed.size(), who + " hears one bed sound");
        assertEquals(expected, bed.getFirst().soundEvent(), who);
        assertEquals(new Vec(-6.5, 65.5, 0.5), Vec.fromPoint(bed.getFirst().origin()), who + " hears it from the bed");
    }

    private static void break0(net.minestom.server.entity.Player breaker, BlockVec at) {
        net.minestom.server.event.EventDispatcher.call(new PlayerBlockBreakEvent(breaker, breaker.getInstance(),
                Block.RED_BED, Block.AIR, at, net.minestom.server.instance.block.BlockFace.TOP));
    }
}
