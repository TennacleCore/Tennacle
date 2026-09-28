package tennacle.games.bedwars;

import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A pop-up tower going up over ticks off a chest that never lands, and an egg's wool trailing five ticks behind. */
class StructuresTest extends HeadlessServerTest {

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
    void theTowerGoesUpTwoCellsATick() {
        round("TowerA", (game, fa, fb) -> {
            Player a = fa.player;
            a.teleport(new Pos(0.5, 66, -3.5, 0, 0)).join(); // facing south: the doorway opens north, toward the placer
            a.setItemInMainHand(ItemStack.of(Material.TRAPPED_CHEST, 2));
            BlockVec p = new BlockVec(0, 66, 0);
            var event = new PlayerBlockPlaceEvent(a, game.instance(), Block.TRAPPED_CHEST, BlockFace.TOP, p,
                    new Pos(0.5, 1, 0.5), PlayerHand.MAIN);
            EventDispatcher.call(event);
            assertTrue(event.isCancelled(), "no chest lands");
            assertEquals(1, a.getItemInMainHand().amount(), "one tower spent");
            ticks(game, 1);
            assertEquals(Block.AIR, game.shard().getBlock(new BlockVec(1, 66, -2)), "the walls wait for the floor");
            ticks(game, 56);
            Block wool = game.shard().getBlock(new BlockVec(1, 66, -2));
            assertTrue(wool.name().endsWith("_wool"), "a wall cell: " + wool.name());
            assertEquals(Block.AIR, game.shard().getBlock(new BlockVec(0, 66, -2)), "the doorway");
            assertEquals(Block.AIR, game.shard().getBlock(new BlockVec(0, 67, -2)), "two high");
            assertTrue(game.shard().getBlock(new BlockVec(0, 68, -2)).name().endsWith("_wool"), "closed above the door");
            Block ladder = game.shard().getBlock(p);
            assertEquals(Block.LADDER, ladder.defaultState(), "the column is a ladder");
            assertEquals("north", ladder.getProperty("facing"), "facing the door");
            assertTrue(game.shard().getBlock(new BlockVec(0, 65, 0)).name().endsWith("_wool"), "wool under the ladder");
            assertTrue(game.shard().getBlock(new BlockVec(-2, 70, 2)).name().endsWith("_wool"), "the platform's far right corner");
            assertTrue(game.shard().getBlock(new BlockVec(2, 72, -3)).name().endsWith("_wool"), "a crenel");
            assertTrue(game.module(Rules.class).building().placed(new BlockVec(1, 66, -2)), "laid as the placer's own");
        });
    }

    @Test
    void anEggLeavesWoolFiveTicksBehind() {
        round("EggA", (game, fa, fb) -> {
            Player a = fa.player;
            BridgeEgg eggs = game.module(BridgeEgg.class);
            Entity egg = new Entity(EntityType.EGG);
            egg.setInstance(game.instance(), new Pos(5.5, 67, 5.5)).join();
            try {
                assertTrue(eggs.allow(a));
                assertFalse(eggs.allow(a), "one a second");
                assertTrue(chat(fa).contains("Please wait 1.0s to use that again"), chat(fa).toString());
                eggs.trail(a, egg);
                ticks(game, 5);
                assertEquals(Block.AIR, game.shard().getBlock(new BlockVec(5, 65, 5)), "nothing yet");
                ticks(game, 1);
                assertTrue(game.shard().getBlock(new BlockVec(5, 65, 5)).name().endsWith("_wool"), "under the egg");
                assertTrue(game.shard().getBlock(new BlockVec(6, 65, 5)).name().endsWith("_wool"), "the nearer x neighbor");
                assertTrue(game.shard().getBlock(new BlockVec(5, 65, 6)).name().endsWith("_wool"), "the nearer z neighbor");
                assertEquals(Block.AIR, game.shard().getBlock(new BlockVec(4, 65, 5)), "not the farther one");
                egg.remove();
                ticks(game, 6);
                assertTrue(game.module(Rules.class).building().placed(new BlockVec(5, 65, 5)), "a member's block");
            } finally {
                egg.remove();
            }
        });
    }

    private static void round(String name, Body body) {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = Matches.launch(solo, Matches.platformFor(null), BedWarsRoundTest.fixture());
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

    private static void ticks(Game game, int n) {
        long until = game.tick() + n;
        BedWarsRoundTest.tickUntil(game, n + " ticks", () -> game.tick() >= until);
    }

    private static List<String> chat(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }
}
