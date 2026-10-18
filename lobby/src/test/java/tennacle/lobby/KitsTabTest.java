package tennacle.lobby;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.canvas.screen.Screens;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.KitLayouts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitsTabTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        LobbyWiring.ensure(instance);
    }

    private static String title(FakePlayer p) {
        Inventory open = Screens.inventory(p.player);
        return open == null ? "" : PlainTextComponentSerializer.plainText().serialize(open.getTitle());
    }

    private static void press(FakePlayer p, Click click) {
        EventDispatcher.call(new InventoryPreClickEvent(Screens.inventory(p.player), p.player, click));
    }

    @Test
    void bedWarsOpensTheHotbarManager() {
        FakePlayer p = FakePlayer.connect(instance, new Pos(70.5, 65, 0.5), "KitTab");
        try {
            assertTrue(KitLayouts.editables().stream().anyMatch(k -> k.id().equals("bedwars") && k.screen() != null));
            assertNull(KitLayouts.open(p.player, "bedwars"));
            assertEquals("Hotbar Manager", title(p), "not the editor in your inventory");
            assertFalse(KitLayouts.editing(p.player));
            String back = PlainTextComponentSerializer.plainText().serialize(
                    Screens.inventory(p.player).getItemStack(48).get(DataComponents.LORE).getFirst());
            assertEquals("To Kits", back);

            press(p, new Click.Right(10)); // Blocks' single items, no game to color them
            assertEquals("Blocks", title(p));
            boolean wool = false;
            for (int i = 18; i < 45; i++) wool |= Screens.inventory(p.player).getItemStack(i).material() == Material.WHITE_WOOL;
            assertTrue(wool, "wool shows white, as the shop does");
        } finally {
            p.player.remove();
        }
    }
}
