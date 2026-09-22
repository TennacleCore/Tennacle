package tennacle.dev;

import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.event.player.PlayerPacketOutEvent;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.client.common.ClientKeepAlivePacket;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.PacketWriting;
import net.minestom.server.network.packet.server.BufferedPacket;
import net.minestom.server.network.packet.server.play.StartConfigurationPacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.common.KeepAlivePacket;
import net.minestom.server.timer.TaskSchedule;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Test-only latency simulator. Delays a chosen player's packets in both directions so the server's perception
 * of them, and their client's picture of everyone else, both trail reality - like a high-ping connection. This
 * lets the laggy-landing knockback (server still sees a fall while the client has already touched down) be
 * reproduced locally without a real high-ping route.
 *
 * <p>Nothing here touches the mechanics library: it only changes <em>when</em> ordinary, vanilla-shaped packets
 * cross the wire, so what's under test is the real code path, not a stubbed latency knob.
 *
 * <h2>Inbound: whole server ticks</h2>
 * Hooks the cancellable {@link PlayerPacketEvent}, which fires for every inbound PLAY packet before its handler
 * runs. A fresh packet is buffered and cancelled; once its delay elapses it is re-fed through the public
 * {@link Player#addPacketToQueue} (documented for "simulating a received packet"), which routes it back through
 * the same path - this time marked to pass straight through.
 *
 * <p>The quantum is a tick and cannot be finer: {@code Player.interpretPacketQueue} is called once per tick from
 * {@code Player.update}, so a packet released mid-tick just waits for the boundary. A sub-tick inbound hold buys
 * jitter, not resolution. An arrival inside a tick therefore rounds UP to the next flush - a hold is never
 * shorter than asked, at the cost of being up to a tick longer for the packets that arrive off the queue.
 *
 * <p><em>Every</em> inbound PLAY packet is delayed, not just movement - true one-way latency. Delaying movement
 * alone left the rest (keep-alive, which drives {@code getLatency()}; swing / use-entity; teleport-confirm acks)
 * arriving on time, so the player was only half-lagged. Lag is set only once the player is already in PLAY, so
 * the login/config handshake is never delayed.
 *
 * <p>A release is rate-capped. {@code addPacketToQueue} offers into a queue bounded at
 * {@code minestom.packet-queue-size} and KICKS with "Too Many Packets" when it is full, while
 * {@code interpretPacketQueue} drains only {@code minestom.packet-per-tick} of it per tick. An uncapped drain
 * pushed a whole backlog in one flush - which is what kicked a lagged player on a second /lag.
 *
 * <h2>Outbound: wall-clock milliseconds</h2>
 * Hooks {@link PlayerPacketOutEvent}, which fires on the connection's own write thread, off the tick loop -
 * so this side really can be held to sub-tick precision, and it is what makes an odd ping like 75ms reachable
 * (one tick in, 25ms out). Releases run on this class's own thread, because Minestom's scheduler cannot do it:
 * {@code TaskSchedule.millis} schedules the wake-up off-tick but {@code SchedulerImpl.safeExecute} hands the
 * body back to the tick queue. {@code sendPacket} is safe from any thread (an MPSC queue) and unparks the
 * writer.
 *
 * <p>Resolution is bounded by {@link #OUT_POLL_MILLIS} and, while a player's send queue sits empty, by the
 * write thread's own idle sleep of half a tick - {@code minestom.new-socket-write-lock} replaces that sleep
 * with an unpark. Under a live packet stream, which is when latency is being measured, the writer is not idle.
 *
 * <p>No rate cap is needed here: the outbound queue is unbounded, so a release cannot kick anyone. It can grow
 * without limit instead, hence {@link #MAX_HELD_OUT}.
 */
public final class LagSimulator {

    /** Outbound release granularity. Finer than the 25ms step the split is built around. */
    private static final long OUT_POLL_MILLIS = 5;

    /** Held outbound packets per player before the oldest is let go early, so a hold cannot grow without end. */
    private static final int MAX_HELD_OUT = 4096;

    /**
     * A held inbound packet and the simulator tick at/after which it should be released. {@code releaseTick} is
     * only ever lowered, by {@link #setInboundTicks}: a shortened pipe delivers what is already in it sooner.
     * Releases stay in arrival order regardless, since only the head is ever polled.
     */
    private static final class Delayed {
        private final ClientPacket packet;
        private final long heldAtNanos = System.nanoTime();
        private volatile long releaseTick;

        Delayed(ClientPacket packet, long releaseTick) {
            this.packet = packet;
            this.releaseTick = releaseTick;
        }

        ClientPacket packet() {
            return packet;
        }

        long releaseTick() {
            return releaseTick;
        }
    }

    /** A packet held on the wall clock rather than the tick - both millisecond pipes use this. */
    private static final class Held<P> {
        private final P packet;
        private final long heldAtNanos = System.nanoTime();
        private volatile long releaseNanos;

        Held(P packet, long releaseNanos) {
            this.packet = packet;
            this.releaseNanos = releaseNanos;
        }

        P packet() {
            return packet;
        }

        long releaseNanos() {
            return releaseNanos;
        }
    }

    /**
     * The simulator's own tick, incremented at the top of each {@link #flush}. Stamping and draining MUST share
     * one clock: comparing against {@code TickSystem} raced task registration order (flush registered first runs
     * before TickSystem's increment within the same server tick), which held every packet a phantom extra tick -
     * one tick of lag measured as ~2 (~100ms) instead of one.
     */
    private volatile long tick = 0;

    /** When {@link #flush} last ran, so an arrival can tell whether it is on a tick boundary or inside one. */
    private volatile long lastFlushNanos = System.nanoTime();

    /** Within this of a flush, an arrival counts as landing on the boundary rather than inside the tick. */
    private static final long ALIGNED_NANOS = TimeUnit.MILLISECONDS.toNanos(2);

    /** Per-player inbound delay in ticks; absent (or {@code <= 0}) means the player is not lagged inbound. */
    private final Map<UUID, Long> delayTicks = new ConcurrentHashMap<>();
    /** Per-player FIFO of held inbound packets, drained in arrival order so movement stays sequenced. */
    private final Map<UUID, Queue<Delayed>> buffers = new ConcurrentHashMap<>();
    /**
     * Packets re-fed and awaiting their second pass through {@link #onPacket}. Identity-keyed: movement packets
     * are value records, so two stationary "same position" packets would otherwise collide. Per player, so an
     * idle simulator can rule itself out with a lock-free size read instead of entering a synchronized set.
     */
    private final Map<UUID, Set<ClientPacket>> inReleased = new ConcurrentHashMap<>();

    /** Per-player outbound delay in milliseconds; absent means the player is not lagged outbound. */
    private final Map<UUID, Long> delayMillis = new ConcurrentHashMap<>();
    private final Map<UUID, Queue<Held<ServerPacket>>> outBuffers = new ConcurrentHashMap<>();

    /**
     * Inbound packets Minestom itself handles off the queue, held on the wall clock instead of the tick.
     * {@code PlayerSocketConnection.IMMEDIATE_PROCESS_PACKETS} names them; in PLAY that is the keep-alive,
     * which it marks "used to calculate latency". Routing it through the tick buffer quantized its hold to
     * whatever was left of the tick, so the ping a client shows read well under what was asked for.
     */
    private final Map<UUID, Queue<Held<ClientPacket>>> preciseIn = new ConcurrentHashMap<>();

    /**
     * What the last keep-alive in each direction was really held for. The ping a client shows comes from that
     * one round trip and nothing else, so when the tab number disagrees with what was asked for, these two say
     * which pipe did not contribute.
     */
    private final Map<UUID, Long> lastKeepAliveOutHold = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastKeepAliveInHold = new ConcurrentHashMap<>();

    private ScheduledExecutorService outClock;

    // half the interpret budget: the live stream the client is still sending needs the other half, or the
    // bounded inbound queue fills and the player is kicked
    private static int releaseBudget() {
        return Math.max(1, ServerFlag.PLAYER_PACKET_PER_TICK / 2);
    }

    /** Registers both packet listeners and starts the two release clocks. Call once after {@code MinecraftServer.init}. */
    public void install() {
        MinecraftServer.getGlobalEventHandler().addListener(PlayerPacketEvent.class, this::onPacket);
        MinecraftServer.getGlobalEventHandler().addListener(PlayerPacketOutEvent.class, this::onOutPacket);
        MinecraftServer.getSchedulerManager().buildTask(this::flush).repeat(TaskSchedule.tick(1)).schedule();
        outClock = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "lag-sim-out");
            thread.setDaemon(true);
            return thread;
        });
        // a throw would silently cancel a fixed-rate task
        outClock.scheduleAtFixedRate(() -> {
            try {
                drainOut();
                drainPreciseIn();
            } catch (Throwable t) {
                MinecraftServer.getExceptionManager().handleException(t);
            }
        }, OUT_POLL_MILLIS, OUT_POLL_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * A keep-alive is sent every {@code minestom.keep-alive-delay} and the connection dropped if the answer has
     * not landed {@code minestom.keep-alive-kick} after that send - and the simulated ping rides on the answer.
     * The real headroom is seconds; this cap only keeps a typo from reading as a mystery timeout.
     */
    public static final long MAX_PING_MILLIS = 2000;

    /**
     * Splits a round trip over the two pipes and returns what was actually applied. Inbound moves in whole
     * ticks and cannot do better, so it takes the nearest tick to half and the outbound millisecond hold
     * carries the remainder - which is what puts an odd ping like 75 within reach at all.
     */
    public long setPing(Player player, long millis) {
        long wanted = Math.max(0, Math.min(millis, MAX_PING_MILLIS));
        long inbound = Math.round(wanted / 2.0 / MinecraftServer.TICK_MS);
        setInboundTicks(player, inbound);
        setOutboundMillis(player, Math.max(0, wanted - inbound * MinecraftServer.TICK_MS));
        return wanted;
    }

    /** Inbound delay in whole server ticks. {@code <= 0} stops delaying new packets (the buffer drains out). */
    public void setInboundTicks(Player player, long ticks) {
        if (ticks <= 0) delayTicks.remove(player.getUuid());
        else delayTicks.put(player.getUuid(), ticks);
        // what is already in the pipe cannot arrive later than the shortened pipe would deliver it. Without this
        // the old head gates the queue for its full original delay and the backlog piles up behind it
        long nanoCeiling = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(0, ticks) * MinecraftServer.TICK_MS);
        Queue<Held<ClientPacket>> precise = preciseIn.get(player.getUuid());
        if (precise != null) {
            for (Held<ClientPacket> one : precise) {
                if (one.releaseNanos > nanoCeiling) one.releaseNanos = nanoCeiling;
            }
        }
        Queue<Delayed> held = buffers.get(player.getUuid());
        if (held == null) return;
        long ceiling = tick + Math.max(0, ticks);
        for (Delayed delayed : held) {
            if (delayed.releaseTick > ceiling) delayed.releaseTick = ceiling;
        }
    }

    /** Outbound delay in milliseconds. {@code <= 0} stops holding new packets (the buffer drains out). */
    public void setOutboundMillis(Player player, long millis) {
        if (millis <= 0) delayMillis.remove(player.getUuid());
        else delayMillis.put(player.getUuid(), millis);
        Queue<Held<ServerPacket>> held = outBuffers.get(player.getUuid());
        if (held == null) return;
        long ceiling = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, millis));
        for (Held<ServerPacket> outgoing : held) {
            if (outgoing.releaseNanos > ceiling) outgoing.releaseNanos = ceiling;
        }
    }

    /** Current inbound delay for the player, in ticks (0 = not lagged). */
    public long inboundTicks(Player player) {
        return delayTicks.getOrDefault(player.getUuid(), 0L);
    }

    /** Current outbound delay for the player, in milliseconds (0 = not lagged). */
    public long outboundMillis(Player player) {
        return delayMillis.getOrDefault(player.getUuid(), 0L);
    }

    /** The round trip this player is being given: both directions, in milliseconds. */
    public long simulatedPing(Player player) {
        return inboundTicks(player) * MinecraftServer.TICK_MS + outboundMillis(player);
    }

    void onPacket(PlayerPacketEvent event) {
        if (idleIn()) return; // every inbound packet on the server lands here; an unused simulator costs four reads
        ClientPacket packet = event.getPacket();
        UUID uuid = event.getPlayer().getUuid();
        // Second pass: re-fed after its delay - let it reach the real handler this time. Ahead of the delay
        // lookup, so one released after the lag is switched off still clears the set instead of sitting in it.
        Set<ClientPacket> back = inReleased.get(uuid);
        if (back != null && back.remove(packet)) return;
        Long delay = delayTicks.get(uuid);
        Queue<Delayed> held = buffers.get(uuid);
        Queue<Held<ClientPacket>> precise = preciseIn.get(uuid);
        boolean draining = (held != null && !held.isEmpty()) || (precise != null && !precise.isEmpty());
        if (delay == null && !draining) return; // not lagged, and nothing of theirs still in flight
        if (offQueue(packet)) {
            preciseIn.computeIfAbsent(uuid, k -> new ConcurrentLinkedQueue<>())
                    .add(new Held<>(packet, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                            (delay == null ? 0 : delay) * MinecraftServer.TICK_MS)));
            event.setCancelled(true);
            return;
        }
        // An unlagged player with a buffer still queues, at zero delay: a new packet must never overtake a held one.
        // A packet that arrives INSIDE a tick rounds up to the next flush. Most inbound packets arrive on a tick
        // (onPacket runs inside interpretPacketQueue), but Minestom handles keep-alive on the read thread, off the
        // queue - and releasing that at the next flush held it for the remainder of the tick, not the tick asked
        // for. Short-changing a latency simulator is the one thing it must not do, so this rounds up, never down.
        long ticks = delay == null ? 0 : delay;
        if (ticks > 0 && System.nanoTime() - lastFlushNanos > ALIGNED_NANOS) ticks++;
        buffers.computeIfAbsent(uuid, k -> new ConcurrentLinkedQueue<>())
                .add(new Delayed(packet, tick + ticks));
        event.setCancelled(true);
    }

    void onOutPacket(PlayerPacketOutEvent event) {
        if (idleOut()) return;
        ServerPacket packet = event.getPacket();
        if (changesState(packet)) return; // a configuration handshake is not a thing to lag
        UUID uuid = event.getPlayer().getUuid();
        Long delay = delayMillis.get(uuid);
        Queue<Held<ServerPacket>> held = outBuffers.get(uuid);
        boolean draining = held != null && !held.isEmpty();
        if (delay == null && !draining) return;
        Queue<Held<ServerPacket>> queue = outBuffers.computeIfAbsent(uuid, k -> new ConcurrentLinkedQueue<>());
        if (queue.size() >= MAX_HELD_OUT) release(event.getPlayer(), queue.poll());
        queue.add(new Held<>(packet, System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(delay == null ? 0 : delay)));
        event.setCancelled(true);
    }

    // ConcurrentHashMap.isEmpty is a counter read, no lock and no iteration - unlike the identity sets
    private boolean idleIn() {
        return delayTicks.isEmpty() && buffers.isEmpty() && preciseIn.isEmpty() && inReleased.isEmpty();
    }

    private boolean idleOut() {
        return delayMillis.isEmpty() && outBuffers.isEmpty();
    }

    /** A packet that moves the connection out of PLAY; framed bytes carry no state transition, so never hold one. */
    private static boolean changesState(ServerPacket packet) {
        return packet instanceof StartConfigurationPacket;
    }

    private void markReleased(UUID uuid, ClientPacket packet) {
        inReleased.computeIfAbsent(uuid,
                k -> Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()))).add(packet);
    }

    /** Whether Minestom processes this one on the read thread, off the player's packet queue. */
    private static boolean offQueue(ClientPacket packet) {
        return packet instanceof ClientKeepAlivePacket;
    }

    void flush() {
        long now = ++tick; // single writer (the scheduler task); volatile for onPacket's reads
        lastFlushNanos = System.nanoTime();
        var iterator = buffers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Queue<Delayed>> entry = iterator.next();
            Player player = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(entry.getKey());
            if (player == null) { // disconnected - drop the buffer and the lag setting
                delayTicks.remove(entry.getKey());
                inReleased.remove(entry.getKey());
                iterator.remove();
                continue;
            }
            Queue<Delayed> queue = entry.getValue();
            Delayed head;
            int budget = releaseBudget();
            while (budget-- > 0 && (head = queue.peek()) != null && head.releaseTick() <= now) {
                queue.poll();
                markReleased(entry.getKey(), head.packet());
                player.addPacketToQueue(head.packet());
            }
            if (queue.isEmpty() && !delayTicks.containsKey(entry.getKey())) iterator.remove();
        }
    }

    void drainOut() {
        long now = System.nanoTime();
        var iterator = outBuffers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Queue<Held<ServerPacket>>> entry = iterator.next();
            Player player = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(entry.getKey());
            if (player == null) {
                delayMillis.remove(entry.getKey());
                iterator.remove();
                continue;
            }
            Queue<Held<ServerPacket>> queue = entry.getValue();
            Held<ServerPacket> head;
            while ((head = queue.peek()) != null && head.releaseNanos() <= now) {
                release(player, queue.poll());
            }
            if (queue.isEmpty() && !delayMillis.containsKey(entry.getKey())) iterator.remove();
        }
    }

    /**
     * Dispatches a due off-queue packet the way the read thread does, rather than re-queueing it - queueing
     * would hand back the tick of jitter this pipe exists to avoid. Same call, off the tick, as
     * {@code PlayerSocketConnection.read} makes for these packet types.
     */
    void drainPreciseIn() {
        long now = System.nanoTime();
        var iterator = preciseIn.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Queue<Held<ClientPacket>>> entry = iterator.next();
            Player player = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(entry.getKey());
            if (player == null) {
                inReleased.remove(entry.getKey());
                iterator.remove();
                continue;
            }
            Queue<Held<ClientPacket>> queue = entry.getValue();
            Held<ClientPacket> head;
            while ((head = queue.peek()) != null && head.releaseNanos() <= now) {
                queue.poll();
                if (head.packet() instanceof ClientKeepAlivePacket) {
                    lastKeepAliveInHold.put(entry.getKey(),
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - head.heldAtNanos));
                }
                markReleased(entry.getKey(), head.packet());
                MinecraftServer.getPacketListenerManager()
                        .processClientPacket(head.packet(), player.getPlayerConnection());
            }
            if (queue.isEmpty() && !delayTicks.containsKey(entry.getKey())) {
                iterator.remove();
                retireInReleased(entry.getKey());
            }
        }
    }

    // only once BOTH inbound pipes are done with them: either can still owe a second pass
    private void retireInReleased(UUID uuid) {
        Queue<Delayed> ticked = buffers.get(uuid);
        Queue<Held<ClientPacket>> precise = preciseIn.get(uuid);
        if ((ticked == null || ticked.isEmpty()) && (precise == null || precise.isEmpty())) {
            inReleased.remove(uuid);
        }
    }

    private void release(Player player, Held<ServerPacket> outgoing) {
        if (outgoing == null) return;
        if (outgoing.packet() instanceof KeepAlivePacket) {
            lastKeepAliveOutHold.put(player.getUuid(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - outgoing.heldAtNanos));
        }
        // framed bytes, NOT the packet: a re-delivery is not a send. It skips PlayerPacketOutEvent, so the
        // replay probe and every other out listener read this packet once, where the server produced it - and it
        // skips sendPacket, whose rewrites and InventorySync mirror already ran and would now eat the slot as a
        // redundant echo
        NetworkBuffer buffer = NetworkBuffer.resizableBuffer(256, MinecraftServer.process());
        PacketWriting.writeFramedPacket(buffer, ConnectionState.PLAY, outgoing.packet(),
                MinecraftServer.getCompressionThreshold());
        player.getPlayerConnection().sendPacket(new BufferedPacket(buffer, 0, buffer.writeIndex()));
    }

    /** How long the last outbound keep-alive was really held, or -1 if none has been held since install. */
    public long lastKeepAliveOutHold(Player player) {
        return lastKeepAliveOutHold.getOrDefault(player.getUuid(), -1L);
    }

    /** How long the last inbound keep-alive was really held, or -1 if none has been held since install. */
    public long lastKeepAliveInHold(Player player) {
        return lastKeepAliveInHold.getOrDefault(player.getUuid(), -1L);
    }

    /** Inbound packets still held for {@code player} - what a lowered delay has yet to let through. */
    int pending(Player player) {
        Queue<Delayed> queue = buffers.get(player.getUuid());
        return queue == null ? 0 : queue.size();
    }

    /** Outbound packets still held for {@code player}. */
    int pendingOut(Player player) {
        Queue<Held<ServerPacket>> queue = outBuffers.get(player.getUuid());
        return queue == null ? 0 : queue.size();
    }
}
