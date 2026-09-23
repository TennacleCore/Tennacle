package tennacle.dev;

import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponent;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventListener;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.event.player.PlayerPacketOutEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.component.CustomData;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.client.play.ClientClickWindowPacket;
import net.minestom.server.network.packet.client.play.ClientCreativeInventoryActionPacket;
import net.minestom.server.network.packet.client.play.ClientHeldItemChangePacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.play.SetCursorItemPacket;
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
 * One player's inventory wire, both ways, on the server's side of the proxy. Items read as their component patch -
 * what the server itself sets - so anything else the client shows was added in the proxy.
 *
 * <p>Pick block before 1.21.4 is the client's own, and it only reports the slot it lands on: a middle-click that
 * logs a held-item change found the stack, one that logs a creative slot built a new one. A click's cursor hash is
 * the client's prediction as the proxy rebuilt it, checked against the server's cursor once the click has run.
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
        inbound = EventListener.of(PlayerPacketEvent.class, e -> {
            record(e.getPlayer(), "IN ", describe(e.getPacket()));
            if (e.getPacket() instanceof ClientClickWindowPacket click) checkCursor(e.getPlayer(), click);
        });
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

    // the event fires before the click runs
    private static void checkCursor(Player player, ClientClickWindowPacket click) {
        player.scheduleNextTick(ignored -> {
            ItemStack cursor = player.getInventory().getCursorItem();
            boolean ok = ItemStack.Hash.of(cursor, MinecraftServer.process()).equals(click.clickedItem());
            record(player, "CHK", "cursor hash " + (ok ? "ok" : "MISMATCH") + " for " + item(cursor));
        });
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
            case ClientHeldItemChangePacket p -> "held -> " + p.slot();
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
            case SetCursorItemPacket p -> "cursor = " + item(p.itemStack());
            default -> null;
        };
    }

    /** Material, count, and the patch: {@code +component} set, {@code -component} removed, custom_data with its keys. */
    private static String item(ItemStack item) {
        if (item == null || item.isAir()) return "air";
        StringBuilder text = new StringBuilder(item.material().name()).append(" x").append(item.amount());
        for (DataComponent.Value entry : item.componentPatch().entrySet()) {
            text.append(entry.value() == null ? " -" : " +").append(entry.component().key().value());
            if (entry.value() instanceof CustomData data) text.append(data.nbt().keySet());
        }
        return text.toString();
    }
}
