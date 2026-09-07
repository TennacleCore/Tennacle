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

    // the countdown holds every break until GO
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
