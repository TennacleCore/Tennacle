package tennacle.lobby;

import io.github.term4.archipelago.shard.ShardView;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.canvas.screen.Screens;
import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.item.ItemDropEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.Material;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.matchmaking.Queues;
import tennacle.lobby.menus.Icons;
import tennacle.lobby.menus.PlayMenu;
import tennacle.lobby.menus.SettingsMenu;
import tennacle.party.Audience;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lobby hotbar: items that stay put, the players dye, the leave dye, and the menus behind the items and NPCs. */
class LobbyHotbarTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        LobbyWiring.ensure(instance);
    }

    private static void use(FakePlayer p, int slot) {
        p.player.setHeldItemSlot((byte) slot);
        EventDispatcher.call(new PlayerUseItemEvent(p.player, PlayerHand.MAIN, p.player.getItemInMainHand(), 0));
    }

    private static void press(FakePlayer p, Click click) {
        EventDispatcher.call(new InventoryPreClickEvent((Inventory) p.player.getOpenInventory(), p.player, click));
    }

    private static void later(FakePlayer p) throws InterruptedException {
        Thread.sleep(220); // past a menu's double-click gap
        for (int i = 0; i < 3; i++) p.player.tick(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())); // past a press's tail
    }

    private static void tick() {
        MinecraftServer.getSchedulerManager().processTick();
        MinecraftServer.getSchedulerManager().processTickEnd();
    }

    @Test
    void hotbarStaysPut() {
        FakePlayer p = FakePlayer.connect(instance, new Pos(40.5, 65, 0.5), "BarPut");
        try {
            Playground.outfit(p.player);
            var inv = p.player.getInventory();
            assertEquals(Material.COMPASS, inv.getItemStack(0).material());
            assertEquals(Material.ENDER_CHEST, inv.getItemStack(4).material());
            assertEquals(Material.LIME_DYE, inv.getItemStack(6).material());
            assertEquals(Material.NETHER_STAR, inv.getItemStack(7).material());
            assertTrue(inv.getItemStack(8).isAir(), "no leave dye outside a queue");
            var click = new InventoryPreClickEvent(inv, p.player, new Click.Left(4));
            EventDispatcher.call(click);
            assertTrue(click.isCancelled(), "the items don't move");
            var drop = new ItemDropEvent(p.player, inv.getItemStack(0));
            EventDispatcher.call(drop);
            assertTrue(drop.isCancelled(), "nor drop");
            p.player.setHeldItemSlot((byte) 4);
            var place = new PlayerBlockPlaceEvent(p.player, instance, Block.ENDER_CHEST, BlockFace.TOP,
                    new BlockVec(40, 64, 0), new Vec(0.5, 1, 0.5), PlayerHand.MAIN);
            EventDispatcher.call(place);
            assertTrue(place.isCancelled(), "nor place, as creative would");
        } finally {
            p.player.remove();
        }
    }

    @Test
    void dyeHidesPlayers() throws InterruptedException {
        FakePlayer p = FakePlayer.connect(instance, new Pos(44.5, 65, 0.5), "BarDye");
        FakePlayer other = FakePlayer.connect(instance, new Pos(46.5, 65, 0.5), "BarOther");
        try {
            Playground.outfit(p.player);
            assertTrue(ShardView.of(p.player).seesEntity(other.player));
            use(p, 6);
            assertFalse(ShardView.of(p.player).seesEntity(other.player), "hidden");
            assertEquals(Material.GRAY_DYE, p.player.getInventory().getItemStack(6).material());
            later(p);
            use(p, 6);
            assertFalse(Settings.playersShown(p.player), "a second press inside the cooldown changes nothing");
            Thread.sleep(1000);
            later(p);
            use(p, 6);
            assertTrue(ShardView.of(p.player).seesEntity(other.player), "shown again");
        } finally {
            Settings.playersShown(p.player, true);
            p.player.remove();
            other.player.remove();
        }
    }

    @Test
    void menuJoinsAndLeaves() throws InterruptedException {
        FakePlayer p = FakePlayer.connect(instance, new Pos(48.5, 65, 0.5), "BarQueue");
        try {
            PlayMenu.open(p.player, "Bridge", mode -> mode.name().equals("bridge"), Icons::of);
            assertNotNull(Screens.screen(p.player));
            press(p, new Click.Left(10)); // the first size, 1v1
            Queues.Waiting waiting = Queues.of(p.player);
            assertNotNull(waiting);
            assertEquals("duels_bridge_duel", waiting.id());
            tick();
            tick();
            assertEquals(Material.RED_DYE, p.player.getInventory().getItemStack(8).material(), "the way out, in the last slot");
            PlayMenu.open(p.player, "Bridge", mode -> mode.name().equals("bridge"), Icons::of);
            assertEquals(Boolean.TRUE, Screens.inventory(p.player).getItemStack(10).get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE),
                    "the size you wait for glints");
            p.player.closeInventory();
            later(p);
            use(p, 8);
            assertNull(Queues.of(p.player), "the dye takes you out");
            tick();
            tick();
            assertTrue(p.player.getInventory().getItemStack(8).isAir(), "and goes with the queue");
        } finally {
            Queues.dequeue(p.player);
            p.player.remove();
        }
    }

    @Test
    void rightClickPicksAMap() throws InterruptedException {
        FakePlayer p = FakePlayer.connect(instance, new Pos(56.5, 65, 0.5), "BarMaps");
        try {
            PlayMenu.open(p.player, "Bridge", mode -> mode.name().equals("bridge"), Icons::of);
            press(p, new Click.Right(10)); // the first size, 1v1
            var maps = Screens.inventory(p.player);
            assertEquals(Material.FIREWORK_STAR, maps.getItemStack(10).material(), "a map a star");
            assertEquals(Material.FIREWORK_ROCKET, maps.getItemStack(40).material(), "Random Map under them");
            assertEquals(Material.ARROW, maps.getItemStack(49).material(), "and the way back");
            assertNull(Queues.of(p.player), "a right click plays nothing");
            later(p);
            press(p, new Click.Left(10));
            Queues.Waiting waiting = Queues.of(p.player);
            assertNotNull(waiting, "the map's click queues");
            assertEquals("duels_bridge_duel", waiting.id());
            assertTrue(waiting.label().contains(" on "), waiting.label());
        } finally {
            Queues.dequeue(p.player);
            p.player.remove();
        }
    }

    @Test
    void settingsStepBothWays() throws InterruptedException {
        FakePlayer p = FakePlayer.connect(instance, new Pos(52.5, 65, 0.5), "BarSet");
        try {
            SettingsMenu.open(p.player);
            press(p, new Click.Left(12));
            assertEquals(Audience.PARTY, Settings.LOBBY_CHAT.of(p.player));
            later(p);
            press(p, new Click.Right(12));
            assertEquals(Audience.EVERYONE, Settings.LOBBY_CHAT.of(p.player));
        } finally {
            p.player.remove();
        }
    }
}
