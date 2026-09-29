package tennacle.games.bedwars;

import tennacle.boot.Mechanics;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;
import tennacle.games.map.Building;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import io.github.term4.polyp.util.tick.TickSystem;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.instance.InstanceTickEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import net.minestom.server.particle.Particle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A sponge drinking its cube ring by ring and vanishing, and the water that stops at a base box or a sponge's cube. */
class SpongeTest extends HeadlessServerTest {

    private static final Pos ROOM = new Pos(0.5, 100, 0.5);

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path replays;

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        tennacle.glue.ReplayBridge.install();
        tennacle.games.watch.Replays.install(replays, new ShardManager());
    }

    @FunctionalInterface
    private interface Body {
        void run(Game game, FakePlayer a, FakePlayer b);
    }

    @Test
    void theSpongeDrinksRingByRing() {
        round("SpongeA", (game, fa, fb) -> {
            Player a = fa.player;
            a.teleport(new Pos(0.5, 66, -3.5, 0, 0)).join();
            a.setItemInMainHand(ItemStack.of(Material.SPONGE, 4));
            BlockVec p = new BlockVec(0, 66, 0);
            for (int x = 1; x <= 4; x++) game.shard().setBlock(new BlockVec(x, 66, 0), Block.WATER);
            var event = new PlayerBlockPlaceEvent(a, game.instance(), Block.SPONGE, BlockFace.TOP, p, new Pos(0.5, 1, 0.5), PlayerHand.MAIN);
            EventDispatcher.call(event);
            assertFalse(event.isCancelled(), "the sponge lands: " + chat(fa));
            game.shard().setBlock(p, Block.SPONGE);
            ticks(game, 4);
            assertEquals(Block.WATER, game.shard().getBlock(new BlockVec(1, 66, 0)), "nothing before the fifth tick");
            fa.sent.clear();
            ticks(game, 1);
            assertEquals(Block.AIR, game.shard().getBlock(new BlockVec(1, 66, 0)), "the first ring drunk");
            assertEquals(Block.WATER, game.shard().getBlock(new BlockVec(2, 66, 0)), "the second waits");
            long clouds = fa.sent(ParticlePacket.class).stream().filter(c -> c.particle() == Particle.CLOUD).count();
            assertEquals(25, clouds, "a cloud at every open cell of the shell: 26 less the water");
            ticks(game, 8);
            assertEquals(Block.AIR, game.shard().getBlock(new BlockVec(2, 66, 0)));
            ticks(game, 16);
            assertEquals(Block.AIR, game.shard().getBlock(new BlockVec(4, 66, 0)), "the fourth ring, 29 ticks in");
            assertEquals(Block.SPONGE, game.shard().getBlock(p), "still standing");
            ticks(game, 8);
            assertEquals(Block.AIR, game.shard().getBlock(p), "gone a step after the last ring");
            assertFalse(Building.of(game).placed(p), "and no longer anyone's block");
        });
    }

    /** The run a drunk source fed dries up past the cube (the user, 2026-09-28: it stood until a block went in). */
    @Test
    void aDrunkSourceTakesItsRun() {
        round("SpongeD", (game, fa, fb) -> {
            Mechanics.fluids.place(game.shard(), new BlockVec(0, 65, -8), Block.WATER);
            ticks(game, 40);
            BlockVec east = new BlockVec(6, 65, -8), west = new BlockVec(-5, 65, -8);
            assertTrue(game.shard().getBlock(east).compare(Block.WATER), "the run reached 6 out");
            assertTrue(game.shard().getBlock(west).compare(Block.WATER));
            fa.player.teleport(new Pos(1.5, 66, -10.5, 0, 0)).join();
            fa.player.setItemInMainHand(ItemStack.of(Material.SPONGE, 4));
            BlockVec p = new BlockVec(1, 65, -8); // the cube reaches x -3..5: the source inside, the run's ends out
            var event = new PlayerBlockPlaceEvent(fa.player, game.instance(), Block.SPONGE, BlockFace.TOP, p, new Pos(0.5, 1, 0.5), PlayerHand.MAIN);
            EventDispatcher.call(event);
            assertFalse(event.isCancelled(), "the sponge lands: " + chat(fa));
            game.shard().setBlock(p, Block.SPONGE);
            ticks(game, 40);
            assertEquals(Block.AIR, game.shard().getBlock(p), "the sponge is done");
            ticks(game, 60);
            assertTrue(game.shard().getBlock(east).isAir(), "the run past the cube dried up: " + game.shard().getBlock(east));
            assertTrue(game.shard().getBlock(west).isAir(), "both ways: " + game.shard().getBlock(west));
        });
    }

    /** A game on the server clock has no tick of its own: the instance's pass carries its water (the user's no-flow). */
    @Test
    void waterFlowsOnTheServerClock() {
        round("ClockA", (game, fa, fb) -> {
            Mechanics.fluids.place(game.shard(), new BlockVec(-8, 65, 4), Block.WATER);
            for (int i = 1; i <= 12; i++) {
                EventDispatcher.call(new InstanceTickEvent(game.instance(), i, 50));
            }
            assertTrue(game.shard().getBlock(new BlockVec(-7, 65, 4)).compare(Block.WATER), "it flows on the instance's own pass");
        });
    }

    @Test
    void waterStopsAtABaseAndAtASponge() {
        round("SpongeB", (game, fa, fb) -> {
            // a bucket, as a player pours it: beside the first base box, x -13..-9
            fa.player.teleport(new Pos(-7.5, 65, 0.5, 0, 90)).join();
            fa.player.setItemInMainHand(ItemStack.of(Material.WATER_BUCKET));
            EventDispatcher.call(new PlayerUseItemEvent(fa.player, PlayerHand.MAIN, fa.player.getItemInMainHand(), 0));
            assertTrue(game.shard().getBlock(new BlockVec(-8, 65, 0)).compare(Block.WATER), "poured at the feet: " + chat(fa));
            assertEquals("0", game.shard().getBlock(new BlockVec(-8, 65, 0)).getProperty("level"), "a source, not a fall");
            assertTrue(fa.player.getItemInMainHand().isAir(), "the spent bucket goes with the water");
            ticks(game, 12);
            assertEquals(Block.AIR, game.shard().getBlock(new BlockVec(-9, 65, 0)), "no water runs into a base");
            assertTrue(game.shard().getBlock(new BlockVec(-7, 65, 0)).compare(Block.WATER), "it runs the other way");
            game.state().set(Scope.GAME, Building.FLOW_RESERVED, true);
            Mechanics.fluids.place(game.shard(), new BlockVec(-8, 65, 2), Block.WATER);
            ticks(game, 12);
            assertTrue(game.shard().getBlock(new BlockVec(-9, 65, 2)).compare(Block.WATER), "the rule lets it in");
            fa.player.teleport(new Pos(0.5, 66, -3.5, 0, 0)).join();
            fa.player.setItemInMainHand(ItemStack.of(Material.SPONGE, 4));
            BlockVec p = new BlockVec(0, 66, 0);
            var event = new PlayerBlockPlaceEvent(fa.player, game.instance(), Block.SPONGE, BlockFace.TOP, p, new Pos(0.5, 1, 0.5), PlayerHand.MAIN);
            EventDispatcher.call(event);
            assertFalse(event.isCancelled(), "the sponge lands: " + chat(fa));
            game.shard().setBlock(p, Block.SPONGE);
            Mechanics.fluids.place(game.shard(), new BlockVec(5, 65, 0), Block.WATER); // just past the cube's edge, x 4
            ticks(game, 12);
            assertEquals(Block.AIR, game.shard().getBlock(new BlockVec(4, 65, 0)), "the cube takes no water while the sponge stands");
            assertTrue(game.shard().getBlock(new BlockVec(6, 65, 0)).compare(Block.WATER));
            ticks(game, 30);
            assertEquals(Block.AIR, game.shard().getBlock(p), "gone");
            Mechanics.fluids.place(game.shard(), new BlockVec(5, 65, 1), Block.WATER);
            ticks(game, 12);
            assertTrue(game.shard().getBlock(new BlockVec(4, 65, 1)).compare(Block.WATER), "and the cube is open again");
        });
    }

    private static void round(String name, Body body) {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = BedWarsRoundTest.byArrival(Matches.launch(solo, Matches.platformFor(null), BedWarsRoundTest.fixture()));
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), name);
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), name + "F");
        try {
            a.player.setInstance(game.instance(), ROOM).join();
            b.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player)));
            assertTrue(Matches.enter(game, List.of(b.player)));
            await("both seated", () -> Game.of(a.player) == game && Game.of(b.player) == game);
            BedWarsRoundTest.live(game);
            assertEquals("1", Match.teamOf(game, a.player), "the fixture's first side");
            a.sent.clear();
            b.sent.clear();
            body.run(game, a, b);
        } finally {
            tennacle.games.watch.Replays.discard(game); // no replay still writing into the temp dir as it goes
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }

    // the harness runs no domain clock: the shard's tick, which the fluids' schedule rides, is driven here beside the game's
    private static void ticks(Game game, int n) {
        long until = game.tick() + n;
        long deadline = System.currentTimeMillis() + 8000;
        while (game.tick() < until && System.currentTimeMillis() < deadline) {
            long before = game.tick();
            MinecraftServer.getSchedulerManager().processTick();
            MinecraftServer.getSchedulerManager().processTickEnd();
            if (game.tick() > before) TickSystem.tickWorld(game.shard(), game.tick());
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertTrue(game.tick() >= until, n + " ticks");
    }

    private static List<String> chat(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }
}
