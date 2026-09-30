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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.TestWiring;
import tennacle.games.matchmaking.Queues;
import tennacle.lobby.menus.Icons;
import tennacle.lobby.menus.PlayMenu;
import tennacle.lobby.menus.SettingsMenu;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lobby hotbar: four items that stay put, the players dye, and the menus behind the items and NPCs. */
class LobbyHotbarTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
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
            assertEquals(Material.LIME_DYE, inv.getItemStack(7).material());
            assertEquals(Material.NETHER_STAR, inv.getItemStack(8).material());
            var click = new InventoryPreClickEvent(inv, p.player, new Click.Left(4));
            EventDispatcher.call(click);
            assertTrue(click.isCancelled(), "the items don't move");
            var drop = new ItemDropEvent(p.player, inv.getItemStack(0));
            EventDispatcher.call(drop);
            assertTrue(drop.isCancelled(), "nor drop");
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
            use(p, 7);
            assertFalse(ShardView.of(p.player).seesEntity(other.player), "hidden");
            assertEquals(Material.GRAY_DYE, p.player.getInventory().getItemStack(7).material());
            later(p);
            use(p, 7);
            assertFalse(Settings.playersShown(p.player), "a second press inside the cooldown changes nothing");
            Thread.sleep(1000);
            later(p);
            use(p, 7);
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
            assertEquals(Boolean.TRUE, Screens.inventory(p.player).getItemStack(10).get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE),
                    "the size you wait for glints");
            later(p);
            press(p, new Click.Left(10));
            assertNull(Queues.of(p.player), "and takes you out");
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
            assertEquals(Settings.Audience.PARTY, Settings.LOBBY_CHAT.of(p.player));
            later(p);
            press(p, new Click.Right(12));
            assertEquals(Settings.Audience.EVERYONE, Settings.LOBBY_CHAT.of(p.player));
        } finally {
            p.player.remove();
        }
    }
}
