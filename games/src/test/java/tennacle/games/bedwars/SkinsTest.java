package tennacle.games.bedwars;

import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.Skins;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.entity.DroppedItemEntity;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.item.PickupItemEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wood wears the buyer's face bought or picked up, shows it in the shop, and leaves as oak planks. */
class SkinsTest extends HeadlessServerTest {

    private static final Pos ROOM = new Pos(0.5, 100, 0.5);
    private static final int WOOD_SLOT = ItemShop.GRID[5];

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path replays;

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        tennacle.glue.ReplayBridge.install();
        tennacle.games.watch.Replays.install(replays, new ShardManager());
    }

    @Test
    void woodWearsYourFace() {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = BedWarsRoundTest.byArrival(Matches.launch(solo, Matches.platformFor(null), BedWarsRoundTest.fixture()));
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer fa = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "SkinA");
        FakePlayer fb = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "SkinB");
        try {
            fa.player.setInstance(game.instance(), ROOM).join();
            fb.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(fa.player)));
            assertTrue(Matches.enter(game, List.of(fb.player)));
            await("both seated", () -> Game.of(fa.player) == game && Game.of(fb.player) == game);
            BedWarsRoundTest.live(game);
            Player a = fa.player;

            a.getInventory().addItemStack(ItemStack.of(Material.GOLD_INGOT, 8));
            ItemShop.open(game, a, Wares.Tab.BLOCKS);
            assertEquals(Material.OAK_PLANKS, Screens.inventory(a).getItemStack(WOOD_SLOT).material(), "no pick: the plain face");
            click(a, WOOD_SLOT);
            assertEquals(16, count(a, Material.OAK_PLANKS));

            Skins.pick(a, BedWars.WOOD, Material.SPRUCE_PLANKS);
            ItemShop.open(game, a, Wares.Tab.BLOCKS);
            assertEquals(Material.SPRUCE_PLANKS, Screens.inventory(a).getItemStack(WOOD_SLOT).material(), "the shop shows the pick");
            click(a, WOOD_SLOT);
            assertEquals(16, count(a, Material.SPRUCE_PLANKS), "and sells it");

            var log = DroppedItemEntity.spawn(game.shard(), a.getPosition(), Vec.ZERO, ItemStack.of(Material.BIRCH_LOG, 3), null, 0);
            assertNotNull(log);
            EventDispatcher.call(new PickupItemEvent(a, log)); // the harness ticks no entity: the pickup is fired by hand
            assertEquals(19, count(a, Material.SPRUCE_PLANKS), "any wood picked up takes the face");
            assertEquals(0, count(a, Material.BIRCH_LOG));

            BlockVec at = new BlockVec(2, 66, 2);
            a.teleport(new Pos(2.5, 66, 0.5)).join(); // within 1.8 reach
            var place = new PlayerBlockPlaceEvent(a, game.instance(), Block.SPRUCE_PLANKS, BlockFace.TOP, at, new Pos(0.5, 1, 0.5), PlayerHand.MAIN);
            EventDispatcher.call(place);
            assertFalse(place.isCancelled());
            game.shard().setBlock(at, Block.SPRUCE_PLANKS);
            EventDispatcher.call(new PlayerBlockBreakEvent(a, game.instance(), Block.SPRUCE_PLANKS, Block.AIR, at, BlockFace.TOP));
            MinecraftServer.getSchedulerManager().processTickEnd();
            ItemEntity fell = game.instance().getEntities().stream()
                    .filter(e -> e instanceof ItemEntity item && item.getPosition().distance(at) < 2)
                    .map(e -> (ItemEntity) e).findFirst().orElse(null);
            assertNotNull(fell, "the break dropped");
            assertEquals(Material.OAK_PLANKS, fell.getItemStack().material(), "out of a block it is plain wood again");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            fa.player.remove();
            fb.player.remove();
        }
    }

    private static void click(Player p, int slot) {
        Inventory open = Screens.inventory(p);
        assertNotNull(open, "a screen is open");
        EventDispatcher.call(new InventoryPreClickEvent(open, p, new Click.Left(slot)));
    }

    private static int count(Player p, Material material) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack in = p.getInventory().getItemStack(i);
            if (in.material() == material) n += in.amount();
        }
        return n;
    }
}
