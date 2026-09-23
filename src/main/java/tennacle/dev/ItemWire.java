package tennacle.dev;

import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventListener;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.event.player.PlayerPacketOutEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.client.play.ClientClickWindowPacket;
import net.minestom.server.network.packet.client.play.ClientCreativeInventoryActionPacket;
import net.minestom.server.network.packet.client.play.ClientHeldItemChangePacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.play.SetPlayerInventorySlotPacket;
import net.minestom.server.network.packet.server.play.SetSlotPacket;
import net.minestom.server.network.packet.server.play.WindowItemsPacket;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The inventory wire for one player, both directions, for reading a pick-block failure on a legacy client.
 *
 * <p>A 1.8 pick block is decided in the client: it looks for the block's item in its own inventory by item and
 * damage and, finding one, sets the held slot, which the client then reports as a held-item change. So a
 * middle-click that logs a held-item change found the stack, and one that logs nothing did not - which is the
 * whole question. Nothing we send carries ViaBackwards' backup tag; it is added in the proxy, so a clean item
 * here is what proves the tag is theirs.
 */
public final class ItemWire {

    private static final int MAX = 200;

    private static final Map<UUID, Queue<String>> lines = new ConcurrentHashMap<>();
    private static final AtomicLong seq = new AtomicLong();
    private static EventListener<PlayerPacketEvent> inbound;
    private static EventListener<PlayerPacketOutEvent> outbound;

    private ItemWire() {}

    public static boolean armed(@NotNull Player player) {
        return lines.containsKey(player.getUuid());
    }

    public static void arm(@NotNull Player player) {
        lines.put(player.getUuid(), new ConcurrentLinkedQueue<>());
        install();
    }

    public static void disarm(@NotNull Player player) {
        lines.remove(player.getUuid());
        if (lines.isEmpty()) uninstall();
    }

    public static @NotNull List<String> read(@NotNull Player player) {
        Queue<String> queue = lines.get(player.getUuid());
        return queue == null ? List.of() : List.copyOf(queue);
    }

    public static void clear(@NotNull Player player) {
        Queue<String> queue = lines.get(player.getUuid());
        if (queue != null) queue.clear();
    }

    private static synchronized void install() {
        if (inbound != null) return;
        inbound = EventListener.of(PlayerPacketEvent.class, e -> record(e.getPlayer(), "IN ", describe(e.getPacket())));
        outbound = EventListener.of(PlayerPacketOutEvent.class, e -> record(e.getPlayer(), "OUT", describe(e.getPacket())));
        MinecraftServer.getGlobalEventHandler().addListener(inbound);
        MinecraftServer.getGlobalEventHandler().addListener(outbound);
    }

    private static synchronized void uninstall() {
        if (inbound == null) return;
        MinecraftServer.getGlobalEventHandler().removeListener(inbound);
        MinecraftServer.getGlobalEventHandler().removeListener(outbound);
        inbound = null;
        outbound = null;
    }

    private static void record(Player player, String way, String what) {
        if (what == null) return;
        Queue<String> queue = lines.get(player.getUuid());
        if (queue == null) return;
        queue.add(seq.incrementAndGet() + " " + way + " " + what);
        while (queue.size() > MAX) queue.poll();
    }

    private static String describe(ClientPacket packet) {
        return switch (packet) {
            case ClientHeldItemChangePacket p -> "held -> " + p.slot(); // a pick block that FOUND the stack
            case ClientCreativeInventoryActionPacket p -> "creative slot " + p.slot() + " = " + item(p.item());
            case ClientClickWindowPacket p -> "click win " + p.windowId() + " slot " + p.slot() + " button " + p.button();
            default -> null;
        };
    }

    private static String describe(ServerPacket packet) {
        return switch (packet) {
            case SetPlayerInventorySlotPacket p -> "slot " + p.slot() + " = " + item(p.itemStack());
            case SetSlotPacket p -> "win " + p.windowId() + " slot " + p.slot() + " = " + item(p.itemStack());
            case WindowItemsPacket p -> "window " + p.windowId() + " x" + p.items().size() + " carried " + item(p.carriedItem());
            default -> null;
        };
    }

    /** Material, count, and any custom_data keys - the tag a legacy client would see as the item's own NBT. */
    private static String item(ItemStack item) {
        if (item == null || item.isAir()) return "air";
        String text = item.material().name() + " x" + item.amount();
        var custom = item.get(DataComponents.CUSTOM_DATA);
        if (custom != null && !custom.nbt().isEmpty()) text += " custom_data" + custom.nbt().keySet();
        return text;
    }
}
