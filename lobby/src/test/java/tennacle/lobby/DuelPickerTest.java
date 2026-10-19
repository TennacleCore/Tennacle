package tennacle.lobby;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.grotto.Game;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.lobby.menus.Icons;
import tennacle.lobby.menus.PlayerMenu;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /duel <player>} with no game opens the lobby's picker, and requests nothing; a right click picks the map. */
class DuelPickerTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        LobbyWiring.ensure(instance);
    }

    private static List<String> plain(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .map(chat -> PlainTextComponentSerializer.plainText().serialize(chat.message())).toList();
    }

    @Test
    void noModeOpensPicker() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(604.5, 65, 604.5), "ReqC");
        FakePlayer b = FakePlayer.connect(instance, new Pos(606.5, 65, 606.5), "ReqD");
        try {
            a.sent.clear();
            MinecraftServer.getCommandManager().execute(a.player, "duel ReqD");
            assertTrue(io.github.term4.canvas.screen.Screens.screen(a.player) != null, "the duel picker opens");
            b.sent.clear();
            MinecraftServer.getCommandManager().execute(b.player, "duel accept ReqC");
            assertTrue(plain(b).stream().anyMatch(l -> l.contains("No duel request from ReqC")), "nothing was requested: " + plain(b));
            for (int i = 0; i < 5; i++) MinecraftServer.getSchedulerManager().processTick();
            assertNull(Game.of(a.player));
        } finally {
            a.player.remove();
            b.player.remove();
        }
    }

    private static void press(FakePlayer p, Click click) {
        EventDispatcher.call(new InventoryPreClickEvent((Inventory) p.player.getOpenInventory(), p.player, click));
    }

    @Test
    void rightClickPicksTheMap() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(608.5, 65, 608.5), "MapA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(610.5, 65, 610.5), "MapB");
        try {
            Mode bridge = Matches.mode("bridge");
            PlayerMenu.duel(a.player, b.player);
            Inventory games = Screens.inventory(a.player);
            int slot = 9;
            while (games.getItemStack(slot).material() != Icons.of(bridge)) slot++;
            press(a, new Click.Right(slot));
            var first = Screens.inventory(a.player).getItemStack(10);
            assertFalse(first.isAir(), "the maps open");
            Thread.sleep(220); // past a menu's double-click gap
            for (int i = 0; i < 3; i++) a.player.tick(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
            b.sent.clear();
            press(a, new Click.Left(10));
            String line = "duel request from MapA on " + PlainTextComponentSerializer.plainText().serialize(first.get(DataComponents.CUSTOM_NAME));
            assertTrue(plain(b).stream().anyMatch(l -> l.contains(line)), "the request names the map: " + plain(b));
        } finally {
            a.player.remove();
            b.player.remove();
        }
    }
}
