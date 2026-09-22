package tennacle.dev;

import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.ServerFlag;
import net.minestom.server.timer.TaskSchedule;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Test-only inbound-lag simulator. Delays a chosen player's movement packets by a fixed number of server ticks
 * before the server processes them, so the server's perception of that player's position + {@code onGround}
 * trails reality - exactly like a high-ping client. This lets the laggy-landing knockback (server still sees a
 * fall while the client has already touched down) be reproduced locally without a real high-ping connection.
 *
 * <p>It hooks Minestom's cancellable {@link PlayerPacketEvent}, which fires for every inbound PLAY packet
 * <em>before</em> its handler runs. A fresh movement packet for a lagged player is buffered and cancelled; once
 * its delay elapses it is re-fed through the public {@link Player#addPacketToQueue} (documented for "simulating a
 * received packet"), which routes it back through the same path - this time marked to pass straight through.
 *
 * <p>Nothing here touches the mechanics library: it only changes <em>when</em> ordinary, vanilla-shaped packets
 * reach the unmodified ground detection, so what's under test is the real code path, not a stubbed latency knob.
 *
 * <p>It delays <em>every</em> inbound PLAY packet for a lagged player, not just the movement ones - true one-way
 * latency. Delaying movement alone left the rest (keep-alive, which drives {@code getLatency()}; swing / use-entity;
 * teleport-confirm acks) arriving on time, so the player was only half-lagged and some server-side state still
 * updated sooner than its position/ground. Lag is set only once the player is already in PLAY, so the login/config
 * handshake is never delayed; the one visible side effect is the spawn-teleport confirm arriving a few ticks late,
 * which self-resolves.
 *
 * <p>A release is rate-capped. {@code Player.addPacketToQueue} offers into a queue bounded at
 * {@code minestom.packet-queue-size} and KICKS with "Too Many Packets" when it is full, while
 * {@code interpretPacketQueue} drains only {@code minestom.packet-per-tick} of it per tick. Lowering the delay
 * makes a whole backlog eligible at once (the new stamps undercut the old ones the FIFO is still holding), and
 * an uncapped drain pushed all of it in one flush - which is what kicked a lagged player on a second /lag.
 */
public final class LagSimulator {

    /** A held packet and the simulator tick at/after which it should be released for real processing. */
    private record Delayed(ClientPacket packet, long releaseTick) {}

    /**
     * The simulator's own tick, incremented at the top of each {@link #flush}. Stamping and draining MUST share
     * one clock: comparing against {@code TickSystem} raced task registration order (flush registered first runs
     * before TickSystem's increment within the same server tick), which held every packet a phantom extra tick -
     * {@code /lag 1} measured as ~2 ticks (~100ms) instead of one.
     */
    private volatile long tick = 0;

    /** Per-player ground-perception delay in ticks; absent (or {@code <= 0}) means the player is not lagged. */
    private final Map<UUID, Long> delayTicks = new ConcurrentHashMap<>();
    /** Per-player FIFO of held packets, drained in arrival order so movement stays sequenced. */
    private final Map<UUID, Queue<Delayed>> buffers = new ConcurrentHashMap<>();
    /**
     * Packets re-fed by {@link #flush} and awaiting their second pass through {@link #onPacket}. Identity-keyed:
     * movement packets are value records, so two stationary "same position" packets would otherwise collide.
     */
    private final Set<ClientPacket> released = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    // half the interpret budget: the live stream the client is still sending needs the other half, or the
    // bounded inbound queue fills and the player is kicked
    private static int releaseBudget() {
        return Math.max(1, ServerFlag.PLAYER_PACKET_PER_TICK / 2);
    }

    /** Registers the packet listener and the per-tick release task. Call once after {@code MinecraftServer.init}. */
    public void install() {
        MinecraftServer.getGlobalEventHandler().addListener(PlayerPacketEvent.class, this::onPacket);
        MinecraftServer.getSchedulerManager().buildTask(this::flush).repeat(TaskSchedule.tick(1)).schedule();
    }

    /** Sets a player's simulated one-way latency in ticks. {@code <= 0} stops delaying new packets (the buffer drains out). */
    public void setDelay(Player player, long ticks) {
        if (ticks <= 0) delayTicks.remove(player.getUuid());
        else delayTicks.put(player.getUuid(), ticks);
    }

    /** Current simulated latency for the player, in ticks (0 = not lagged). */
    public long getDelay(Player player) {
        return delayTicks.getOrDefault(player.getUuid(), 0L);
    }

    void onPacket(PlayerPacketEvent event) {
        ClientPacket packet = event.getPacket();
        // Second pass: re-fed by flush after its delay - let it reach the real handler this time. Ahead of the
        // delay lookup, so one released after /lag 0 still clears the set instead of sitting in it for good.
        if (released.remove(packet)) return;
        UUID uuid = event.getPlayer().getUuid();
        Long delay = delayTicks.get(uuid);
        Queue<Delayed> held = buffers.get(uuid);
        boolean draining = held != null && !held.isEmpty();
        if (delay == null && !draining) return; // not lagged, and nothing of theirs still in flight
        // First pass: hold it and drop it from this tick's processing. Every inbound packet is delayed (true one-way
        // latency), so nothing about this player - position, ground flag, ping, swings - updates sooner than the rest.
        // An unlagged player with a buffer still queues, at zero delay: a new packet must never overtake a held one.
        buffers.computeIfAbsent(uuid, k -> new ConcurrentLinkedQueue<>())
                .add(new Delayed(packet, tick + (delay == null ? 0 : delay)));
        event.setCancelled(true);
    }

    void flush() {
        long now = ++tick; // single writer (the scheduler task); volatile for onPacket's reads
        var iterator = buffers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Queue<Delayed>> entry = iterator.next();
            Player player = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(entry.getKey());
            if (player == null) { // disconnected - drop the buffer and the lag setting
                delayTicks.remove(entry.getKey());
                iterator.remove();
                continue;
            }
            Queue<Delayed> queue = entry.getValue();
            Delayed head;
            int budget = releaseBudget();
            while (budget-- > 0 && (head = queue.peek()) != null && head.releaseTick() <= now) {
                queue.poll();
                released.add(head.packet());
                player.addPacketToQueue(head.packet());
            }
            if (queue.isEmpty() && !delayTicks.containsKey(entry.getKey())) iterator.remove();
        }
    }

    /** Packets still held for {@code player} - what a lowered delay has yet to let through. */
    int pending(Player player) {
        Queue<Delayed> queue = buffers.get(player.getUuid());
        return queue == null ? 0 : queue.size();
    }
}
