package tennacle.games;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.kit.Kit;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.item.ItemDropEvent;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.lobby.Hotbar;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import tennacle.lobby.LobbyWiring;

/** The kit lands in your own inventory, armor locked or free per kit, and closing the inventory saves it. */
class KitEditorTest extends HeadlessServerTest {

    private static final Kit FREE = Kit.of("armortest")
            .item("sword", 0, ItemStack.of(Material.IRON_SWORD))
            .armor(EquipmentSlot.HELMET, ItemStack.of(Material.IRON_HELMET))
            .armorMoves(true);

    @BeforeAll
    static void wire() {
        LobbyWiring.ensure(instance);
        KitLayouts.editable("Armor test", FREE);
    }

    private static void close(FakePlayer p) {
        EventDispatcher.call(new InventoryCloseEvent(p.player.getInventory(), p.player, true));
    }

    @Test
    void bridgeArmorStaysOn() {
        FakePlayer p = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "KitLock");
        try {
            tennacle.lobby.Playground.outfit(p.player);
            assertNull(KitLayouts.open(p.player, "bridge"));
            assertTrue(KitLayouts.editing(p.player));
            ItemStack chest = p.player.getEquipment(EquipmentSlot.CHESTPLATE);
            assertEquals(Material.LEATHER_CHESTPLATE, chest.material(), "the kit's armor is worn");
            assertNotNull(chest.get(DataComponents.DYED_COLOR), "in a team's color");
            Material blocks = p.player.getInventory().getItemStack(3).material();
            assertTrue(blocks.key().value().endsWith("_terracotta") && blocks != Material.TERRACOTTA, "blocks too: " + blocks);

            var click = new InventoryPreClickEvent(p.player.getInventory(), p.player, new Click.Left(42));
            EventDispatcher.call(click);
            assertTrue(click.isCancelled(), "the chestplate stays on");
            var drop = new ItemDropEvent(p.player, ItemStack.of(Material.IRON_SWORD));
            EventDispatcher.call(drop);
            assertTrue(drop.isCancelled(), "nothing drops while arranging");

            var inv = p.player.getInventory();
            ItemStack sword = inv.getItemStack(0);
            inv.setItemStack(0, ItemStack.AIR);
            inv.setItemStack(6, sword);
            close(p);
            assertFalse(KitLayouts.editing(p.player));
            assertEquals(6, KitLayouts.of(p.player, "bridge").get("sword"));
            assertTrue(Hotbar.isLobbyItem(inv.getItemStack(0)), "the lobby items come back");
            assertTrue(p.player.getEquipment(EquipmentSlot.CHESTPLATE).isAir(), "and the armor goes");
        } finally {
            p.player.remove();
        }
    }

    @Test
    void freeArmorCarries() {
        FakePlayer p = FakePlayer.connect(instance, new Pos(4.5, 65, 0.5), "KitFree");
        try {
            tennacle.lobby.Playground.outfit(p.player);
            assertNull(KitLayouts.open(p.player, "armortest"));
            var click = new InventoryPreClickEvent(p.player.getInventory(), p.player, new Click.Left(41));
            EventDispatcher.call(click);
            assertFalse(click.isCancelled(), "this kit's armor moves");
            p.player.getInventory().setItemStack(20, p.player.getEquipment(EquipmentSlot.HELMET));
            p.player.setEquipment(EquipmentSlot.HELMET, ItemStack.AIR);
            close(p);
            Map<String, Integer> saved = KitLayouts.of(p.player, "armortest");
            assertEquals(20, saved.get(Kit.armorName(EquipmentSlot.HELMET)), "starts with the helmet off");
            assertNotEquals(null, saved.get("sword"));
        } finally {
            p.player.remove();
        }
    }
}
