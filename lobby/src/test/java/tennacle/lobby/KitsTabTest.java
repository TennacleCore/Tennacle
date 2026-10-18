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

    @Test
    void importLayoutsOpens() {
        FakePlayer p = FakePlayer.connect(instance, new Pos(72.5, 65, 0.5), "KitImp");
        try {
            tennacle.lobby.menus.MainMenu.open(p.player);
            press(p, new Click.Left(2)); // the Kits tab
            assertEquals("Kits", title(p));
            press(p, new Click.Left(40));
            assertEquals("Import Layouts", title(p), "a mode to choose first");
            Inventory open = Screens.inventory(p.player);
            int bedwars = -1;
            for (int i = 0; i < open.getSize(); i++) if (open.getItemStack(i).material() == Material.RED_BED) bedwars = i;
            assertTrue(bedwars >= 0, "Bed Wars among the modes");
            press(p, new Click.Left(bedwars));
            assertEquals("Import Bed Wars", title(p));
            open = Screens.inventory(p.player);
            assertEquals(Material.NETHER_STAR, open.getItemStack(10).material(), "the quick buy, its own row");
            assertEquals(Material.BLAZE_POWDER, open.getItemStack(19).material(), "the hotbar, its own");
            assertEquals(Material.GOLD_BLOCK, open.getItemStack(12).material(), "a source to take the quick buy from");
            tennacle.games.shop.Favorites.set(p.player, "bedwars:items", java.util.Collections.nCopies(21, ""));
            press(p, new Click.Left(12));
            assertEquals("", tennacle.games.shop.Favorites.of(p.player, "bedwars:items").getFirst(), "one click imports nothing");
        } finally {
            p.player.remove();
        }
    }

    @Test
    void playMenuEditorStaysOpen() {
        FakePlayer p = FakePlayer.connect(instance, new Pos(74.5, 65, 0.5), "KitPlay");
        try {
            tennacle.lobby.menus.PlayMenu.open(p.player, "Bed Wars", mode -> mode.name().startsWith("bedwars"),
                    tennacle.lobby.menus.Icons::of);
            press(p, new Click.Left(32));
            assertEquals("Hotbar Manager", title(p), "not closed straight after it opens");
        } finally {
            p.player.remove();
        }
    }

    @Test
    void toolsOpenTheToolsTab() {
        FakePlayer p = FakePlayer.connect(instance, new Pos(76.5, 65, 0.5), "KitTools");
        try {
            assertNull(KitLayouts.open(p.player, "bedwars"));
            press(p, new Click.Right(12)); // Pickaxe
            assertEquals("Tools", title(p));
            boolean pickaxe = false;
            for (int i = 18; i < 45; i++) pickaxe |= Screens.inventory(p.player).getItemStack(i).material() == Material.WOODEN_PICKAXE;
            assertTrue(pickaxe, "the pickaxe as a single item");
        } finally {
            p.player.remove();
        }
    }

    @Test
    void quickBuyEditsInTheLobby() {
        FakePlayer p = FakePlayer.connect(instance, new Pos(78.5, 65, 0.5), "KitQuick");
        try {
            assertNull(KitLayouts.open(p.player, "bedwars"));
            press(p, new Click.Left(47));
            assertEquals("Quick Buy", title(p));
            press(p, new Click.Left(1)); // Blocks
            assertEquals("Blocks", title(p));
            press(p, new Click.Left(19)); // wool, the first ware
            assertEquals("Adding to Quick Buy...", title(p));
            press(p, new Click.Left(20)); // the second pin
            assertEquals("Quick Buy", title(p));
            var pins = tennacle.games.shop.Favorites.of(p.player, "bedwars:items");
            assertEquals("wool", pins.get(1));
            press(p, new Click.LeftShift(20));
            assertEquals("", tennacle.games.shop.Favorites.of(p.player, "bedwars:items").get(1), "shift click takes it off");
            press(p, new Click.Left(49));
            assertEquals("Hotbar Manager", title(p), "Go Back leads where it opened from");
        } finally {
            p.player.remove();
        }
    }

    @Test
    void kitImportOpensTheMenu() {
        FakePlayer p = FakePlayer.connect(instance, new Pos(80.5, 65, 0.5), "KitCmd");
        try {
            net.minestom.server.MinecraftServer.getCommandManager().execute(p.player, "kit import");
            assertEquals("Import Layouts", title(p), "no import yet, only the choice");
            net.minestom.server.MinecraftServer.getCommandManager().execute(p.player, "kit import bridge");
            assertEquals("Import Bridge", title(p));
        } finally {
            p.player.remove();
        }
    }
}
