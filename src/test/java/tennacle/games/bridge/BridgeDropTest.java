package tennacle.games.bridge;

import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.polyp.api.event.item.ItemSpawnEvent;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.EventListener;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A broken bridge block drops itself: the clay comes back as the clay. */
class BridgeDropTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void brokenClayDrops() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(600.5, 65, 600.5), "DropA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(602.5, 65, 602.5), "DropB");
        Game game = null;
        List<ItemSpawnEvent> spawned = new CopyOnWriteArrayList<>();
        EventListener<ItemSpawnEvent> ear = EventListener.of(ItemSpawnEvent.class, spawned::add);
        MinecraftServer.getGlobalEventHandler().addListener(ear);
        try {
            Mode bridge = Matches.mode("bridge");
            assertNotNull(bridge);
            game = Matches.start(bridge, Matches.platformFor(bridge, "scrims"), List.of(List.of(a.player), List.of(b.player)));
            long deadline = System.currentTimeMillis() + 4000;
            while (Game.of(a.player) == null && System.currentTimeMillis() < deadline) {
                tick();
                Thread.sleep(10);
            }
            assertNotNull(Game.of(a.player));
            int ticks = 0; // the cage countdown refuses every break; the harness has no ticker, so the phases are driven here
            while (!Match.live(game) && ticks++ < 2000) tick();
            assertTrue(Match.live(game), "the match went live");
            a.player.setHeldItemSlot((byte) 2); // the kit's pickaxe
            assertEquals(Material.DIAMOND_PICKAXE, a.player.getItemInMainHand().material(), "the kit landed");

            BlockVec inBridge = new BlockVec(5, 88, 0); // inside the +x section of test.bncl
            Block clay = game.instance().getBlock(inBridge);
            EventDispatcher.call(new PlayerBlockBreakEvent(a.player, game.instance(), clay, Block.AIR, inBridge, BlockFace.TOP));
            for (int i = 0; i < 3; i++) tick(); // the drop lands end of tick
            assertEquals(1, spawned.size(), "one break, one drop");
            assertEquals(Material.fromKey(clay.key()), spawned.getFirst().item().getItemStack().material(), "and it is the block");
        } finally {
            MinecraftServer.getGlobalEventHandler().removeListener(ear);
            if (game != null) game.end(Outcome.draw());
            a.player.remove();
            b.player.remove();
        }
    }

    private static void tick() {
        MinecraftServer.getSchedulerManager().processTick();
        MinecraftServer.getSchedulerManager().processTickEnd();
    }
}
