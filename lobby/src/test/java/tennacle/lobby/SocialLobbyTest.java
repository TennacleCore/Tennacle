package tennacle.lobby;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.lobby.menus.PlayerMenu;
import tennacle.lobby.menus.SettingsMenu;
import tennacle.social.Audience;
import tennacle.social.Whereabouts;
import tennacle.social.friends.Friends;
import tennacle.social.messages.Messages;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocialLobbyTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        LobbyWiring.ensure(instance);
    }

    private static List<String> chat(FakePlayer p, String command) {
        p.sent.clear();
        MinecraftServer.getCommandManager().execute(p.player, command);
        return p.sent(SystemChatPacket.class).stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }

    private static Material at(FakePlayer p, int slot) {
        return ((Inventory) p.player.getOpenInventory()).getItemStack(slot).material();
    }

    private static void press(FakePlayer p, Click click) {
        EventDispatcher.call(new InventoryPreClickEvent((Inventory) p.player.getOpenInventory(), p.player, click));
    }

    private static void later(FakePlayer p) throws InterruptedException {
        Thread.sleep(220); // past a menu's double-click gap
        for (int i = 0; i < 3; i++) p.player.tick(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())); // past a press's tail
    }

    @Test
    void aFriendRowSaysWhere() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(60.5, 65, 0.5), "SoA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(62.5, 65, 0.5), "SoB");
        try {
            assertEquals("is in the Main Lobby", Whereabouts.of(b.player));
            chat(a, "f SoB");
            chat(b, "f accept SoA");
            List<String> list = chat(a, "fl");
            assertTrue(list.getFirst().contains("SoB is in the Main Lobby"), list.toString());
            String json = a.sent(SystemChatPacket.class).stream()
                    .map(c -> GsonComponentSerializer.gson().serialize(c.message())).findFirst().orElseThrow();
            assertTrue(json.contains("/friend view SoB"), "an online row opens the player menu: " + json);
            chat(a, "friend view SoB");
            assertTrue(a.player.getOpenInventory() instanceof Inventory, "the player menu");
            assertEquals(Material.BOOK, at(a, 21), "already friends: Remove Friend");
            assertEquals(Material.HOPPER, at(a, 23));
        } finally {
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void theMenuSendsARequest() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(64.5, 65, 0.5), "SoC");
        FakePlayer b = FakePlayer.connect(instance, new Pos(66.5, 65, 0.5), "SoD");
        try {
            PlayerMenu.open(a.player, b.player);
            assertEquals(Material.WRITABLE_BOOK, at(a, 21));
            b.sent.clear();
            press(a, new Click.Left(21));
            assertTrue(b.sent(SystemChatPacket.class).stream()
                    .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message()))
                    .anyMatch(l -> l.contains("Friend request from SoC")), "the click sends it");
        } finally {
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void settingsCarryTheSocialRows() throws InterruptedException {
        FakePlayer p = FakePlayer.connect(instance, new Pos(68.5, 65, 0.5), "SoE");
        try {
            SettingsMenu.open(p.player);
            assertEquals(Material.OAK_SIGN, at(p, 28));
            press(p, new Click.Left(28));
            assertEquals(Audience.FRIENDS, Messages.PRIVACY.of(p.player));
            assertEquals(Material.CHEST, at(p, 34), "kit visibility");
            assertEquals(Audience.EVERYONE, tennacle.games.imports.LayoutVisibility.VISIBILITY.of(p.player), "everyone by default");
            later(p);
            press(p, new Click.Left(14));
            assertEquals(Friends.Notices.BEST, Friends.notices(p.player));
        } finally {
            p.player.remove();
        }
    }
}
