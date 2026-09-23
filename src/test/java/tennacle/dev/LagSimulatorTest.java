package tennacle.dev;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.event.player.PlayerPacketOutEvent;
import net.minestom.server.network.packet.client.common.ClientKeepAlivePacket;
import net.minestom.server.network.packet.client.play.ClientPlayerPositionPacket;
import net.minestom.server.network.packet.server.play.UpdateHealthPacket;
import io.github.term4.polyp.platform.inventory.InventorySync;
import io.github.term4.polyp.platform.player.OptimizedPlayer;
import net.minestom.server.event.EventListener;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.SetPlayerInventorySlotPacket;
import net.minestom.server.network.packet.server.play.SetSlotPacket;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.PacketReading;
import net.minestom.server.network.packet.PacketReading.ParsedPacket;
import net.minestom.server.network.packet.PacketReading.Result;
import net.minestom.server.network.packet.server.BufferedPacket;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.ServerPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import java.util.List;

import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reported from games: a second /lag kicked the player with "Too Many Packets". The inbound queue Minestom
 * re-feeds into is bounded and drains at a fixed rate per tick, so a backlog cannot be handed over in one go.
 * The outbound side rides the write thread instead, which is what lets a ping land off a tick boundary.
 */
class LagSimulatorTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        tennacle.games.TestWiring.ensure(instance); // the inventory case needs the real player class and its fixes
    }

    private static int budget() {
        return Math.max(1, ServerFlag.PLAYER_PACKET_PER_TICK / 2);
    }

    private static PlayerPacketEvent move(Player player, int i) {
        return new PlayerPacketEvent(player, new ClientPlayerPositionPacket(new Vec(i, 70, 0), (byte) 1));
    }

    private static PlayerPacketOutEvent out(Player player, float health) {
        return new PlayerPacketOutEvent(player, new UpdateHealthPacket(health, 20, 5f));
    }

    /** Lowering the delay makes the whole backlog eligible at once; it has to leave over several ticks. */
    @Test
    void aBacklogLeavesInSteps() {
        LagSimulator sim = new LagSimulator();
        FakePlayer p = FakePlayer.connect(instance, new Pos(900.5, 70, 900.5), "LagStep");
        try {
            sim.setInboundTicks(p.player, 200);
            for (int i = 0; i < 300; i++) sim.onPacket(move(p.player, i));
            assertEquals(300, sim.pending(p.player), "all held while the delay is long");

            sim.setInboundTicks(p.player, 1);
            for (int i = 300; i < 350; i++) sim.onPacket(move(p.player, i));

            sim.flush(); // the shortened pipe brings the whole backlog due at once
            assertEquals(350 - budget(), sim.pending(p.player), "one tick releases one budget, not the pile");
            sim.flush();
            assertEquals(350 - 2 * budget(), sim.pending(p.player), "and it keeps stepping");
        } finally {
            sim.setInboundTicks(p.player, 0);
            p.player.remove();
        }
    }

    /** /lag 0 must not let a fresh packet past the ones still held: the server would see the player snap back. */
    @Test
    void nothingOvertakesAHeldPacket() {
        LagSimulator sim = new LagSimulator();
        FakePlayer p = FakePlayer.connect(instance, new Pos(910.5, 70, 900.5), "LagOrder");
        try {
            sim.setInboundTicks(p.player, 5);
            sim.onPacket(move(p.player, 0));
            assertEquals(1, sim.pending(p.player));

            sim.setInboundTicks(p.player, 0);
            PlayerPacketEvent after = move(p.player, 1);
            sim.onPacket(after);
            assertTrue(after.isCancelled(), "it queues behind the held one rather than passing through");
            assertEquals(2, sim.pending(p.player));

            for (int t = 0; t < 10; t++) sim.flush();
            assertEquals(0, sim.pending(p.player), "and the buffer drains out once the delay is off");

            PlayerPacketEvent clear = move(p.player, 2);
            sim.onPacket(clear);
            assertFalse(clear.isCancelled(), "an empty buffer stops intercepting");
        } finally {
            sim.setInboundTicks(p.player, 0);
            p.player.remove();
        }
    }

    /**
     * The point of holding outbound separately: inbound moves in 50ms steps, so a ping that is not a multiple
     * of one is only reachable if the remainder rides the millisecond side.
     */
    @Test
    void aPingSplitsOverBothPipes() {
        LagSimulator sim = new LagSimulator();
        FakePlayer p = FakePlayer.connect(instance, new Pos(920.5, 70, 900.5), "LagSplit");
        int tickMs = MinecraftServer.TICK_MS;
        try {
            sim.setPing(p.player, 75);
            assertEquals(1, sim.inboundTicks(p.player));
            assertEquals(75 - tickMs, sim.outboundMillis(p.player), "the odd 25 is what the tick cannot carry");
            assertEquals(75, sim.simulatedPing(p.player));

            sim.setPing(p.player, 100);
            assertEquals(1, sim.inboundTicks(p.player), "an even ping splits down the middle");
            assertEquals(50, sim.outboundMillis(p.player));

            sim.setPing(p.player, 25);
            assertEquals(0, sim.inboundTicks(p.player), "under half a tick there is nothing to round to");
            assertEquals(25, sim.outboundMillis(p.player));

            assertEquals(LagSimulator.MAX_PING_MILLIS, sim.setPing(p.player, 60_000), "capped, not accepted");

            sim.setPing(p.player, 0);
            assertEquals(0, sim.simulatedPing(p.player));
        } finally {
            sim.setPing(p.player, 0);
            p.player.remove();
        }
    }

    /** An outbound packet is held on the wall clock, not the tick, so nothing releases it early. */
    @Test
    void anOutboundHoldWaitsOutItsMillis() throws InterruptedException {
        LagSimulator sim = new LagSimulator();
        FakePlayer p = FakePlayer.connect(instance, new Pos(930.5, 70, 900.5), "LagOut");
        try {
            sim.setOutboundMillis(p.player, 30);
            PlayerPacketOutEvent held = out(p.player, 20f);
            sim.onOutPacket(held);
            assertTrue(held.isCancelled(), "it does not reach the socket on this pass");
            assertEquals(1, sim.pendingOut(p.player));

            sim.drainOut();
            assertEquals(1, sim.pendingOut(p.player), "not due yet - no tick boundary involved either way");

            Thread.sleep(80);
            sim.drainOut();
            assertEquals(0, sim.pendingOut(p.player), "released once its milliseconds are up");
        } finally {
            sim.setOutboundMillis(p.player, 0);
            p.player.remove();
        }
    }

    /**
     * Minestom handles keep-alive on the read thread, off the queue, so it arrives inside a tick rather than on
     * one. Releasing that at the next flush held it for the remainder of the tick - a 50ms delay measured 23.
     */
    @Test
    void anArrivalInsideATickRoundsUp() throws InterruptedException {
        LagSimulator sim = new LagSimulator();
        FakePlayer p = FakePlayer.connect(instance, new Pos(950.5, 70, 900.5), "LagMidTick");
        try {
            sim.setInboundTicks(p.player, 1);
            sim.flush(); // a tick boundary
            Thread.sleep(20); // now we are inside it, where a read-thread packet lands
            sim.onPacket(move(p.player, 0));

            sim.flush();
            assertEquals(1, sim.pending(p.player), "the next flush is not a full tick away - it waits");
            sim.flush();
            assertEquals(0, sim.pending(p.player), "and goes out on the one after");
        } finally {
            sim.setInboundTicks(p.player, 0);
            p.player.remove();
        }
    }

    /** A packet that lands on the boundary still gets exactly what was asked for, not a tick more. */
    @Test
    void anArrivalOnTheTickIsExact() {
        LagSimulator sim = new LagSimulator();
        FakePlayer p = FakePlayer.connect(instance, new Pos(960.5, 70, 900.5), "LagOnTick");
        try {
            sim.setInboundTicks(p.player, 1);
            sim.flush();
            sim.onPacket(move(p.player, 0)); // immediately after, as interpretPacketQueue runs
            sim.flush();
            assertEquals(0, sim.pending(p.player), "one tick asked, one tick served");
        } finally {
            sim.setInboundTicks(p.player, 0);
            p.player.remove();
        }
    }

    /**
     * The keep-alive is the only thing a client ping is measured from, and Minestom takes it off the queue on
     * the read thread. It gets the millisecond pipe, so its hold is what was asked for rather than whatever
     * remained of the tick.
     */
    @Test
    void theKeepAliveIsHeldOnTheClock() throws InterruptedException {
        LagSimulator sim = new LagSimulator();
        FakePlayer p = FakePlayer.connect(instance, new Pos(970.5, 70, 900.5), "LagKeepAlive");
        try {
            sim.setInboundTicks(p.player, 1); // 50ms
            PlayerPacketEvent ka = new PlayerPacketEvent(p.player, new ClientKeepAlivePacket(1234L));
            sim.onPacket(ka);
            assertTrue(ka.isCancelled(), "held, not passed to the latency listener yet");
            assertEquals(0, sim.pending(p.player), "and not in the tick buffer, which would round it");

            for (int t = 0; t < 4; t++) sim.flush(); // ticks alone must not release it
            sim.drainPreciseIn();
            assertEquals(-1, sim.lastKeepAliveInHold(p.player), "still inside its 50ms");

            Thread.sleep(80);
            sim.drainPreciseIn();
            assertTrue(sim.lastKeepAliveInHold(p.player) >= 50,
                    "held at least what was asked: " + sim.lastKeepAliveInHold(p.player) + "ms");
        } finally {
            sim.setInboundTicks(p.player, 0);
            p.player.remove();
        }
    }

    /** With nobody lagged the listeners run for every packet on the server, so they must bail before any lock. */
    @Test
    void anIdleSimulatorLetsEverythingBy() {
        LagSimulator sim = new LagSimulator();
        FakePlayer p = FakePlayer.connect(instance, new Pos(980.5, 70, 900.5), "LagIdle");
        try {
            PlayerPacketEvent in = move(p.player, 0);
            sim.onPacket(in);
            assertFalse(in.isCancelled());
            PlayerPacketOutEvent outbound = out(p.player, 20f);
            sim.onOutPacket(outbound);
            assertFalse(outbound.isCancelled());

            // and it goes back to idle once a lagged player is done, or the gate would stay open for good
            sim.setInboundTicks(p.player, 1);
            sim.onPacket(move(p.player, 1));
            sim.setInboundTicks(p.player, 0);
            for (int t = 0; t < 4; t++) sim.flush();
            PlayerPacketEvent after = move(p.player, 2);
            sim.onPacket(after);
            assertFalse(after.isCancelled(), "the simulator let go of this player entirely");
        } finally {
            sim.setInboundTicks(p.player, 0);
            p.player.remove();
        }
    }

    /** A grouped send hands every viewer ONE packet instance, so the pass-through mark cannot be global. */
    @Test
    void oneSharedPacketIsHeldPerPlayer() throws InterruptedException {
        LagSimulator sim = new LagSimulator();
        FakePlayer a = FakePlayer.connect(instance, new Pos(940.5, 70, 900.5), "LagShareA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(942.5, 70, 900.5), "LagShareB");
        try {
            sim.setOutboundMillis(a.player, 20);
            sim.setOutboundMillis(b.player, 20);
            UpdateHealthPacket shared = new UpdateHealthPacket(11f, 20, 5f);

            PlayerPacketOutEvent toA = new PlayerPacketOutEvent(a.player, shared);
            sim.onOutPacket(toA);
            assertTrue(toA.isCancelled());
            Thread.sleep(60);
            sim.drainOut(); // releases it for A, marking that instance as A's pass-through
            assertEquals(0, sim.pendingOut(a.player));

            PlayerPacketOutEvent toB = new PlayerPacketOutEvent(b.player, shared);
            sim.onOutPacket(toB);
            assertTrue(toB.isCancelled(), "B's copy is still B's to hold");
            assertEquals(1, sim.pendingOut(b.player));
        } finally {
            sim.setOutboundMillis(a.player, 0);
            sim.setOutboundMillis(b.player, 0);
            a.player.remove();
            b.player.remove();
        }
    }

    /**
     * Reported from games: /lag broke the inventory. The hold captures at {@link PlayerPacketOutEvent}, downstream
     * of everything {@code OptimizedPlayer.sendPacket} does, the inventory sync included, so the release must not
     * run any of it a second time.
     */
    @Test
    void aHeldSlotStillReachesTheClient() {
        LagSimulator sim = new LagSimulator();
        EventListener<PlayerPacketOutEvent> hold = EventListener.of(PlayerPacketOutEvent.class, sim::onOutPacket);
        MinecraftServer.getGlobalEventHandler().addListener(hold);
        FakePlayer p = FakePlayer.connect(instance, new Pos(960.5, 70, 900.5), "LagBag");
        InventorySync sync = ((OptimizedPlayer) p.player).inventorySync();
        try {
            p.player.getInventory().setItemStack(0, ItemStack.of(Material.DIAMOND_SWORD));
            sync.broadcast();
            p.sent.clear();

            sim.setOutboundMillis(p.player, 30);
            p.player.getInventory().setItemStack(0, ItemStack.of(Material.BOW));
            sync.broadcast();
            assertEquals(0, slotUpdates(p), "held, so nothing on the wire yet");
            assertTrue(sim.pendingOut(p.player) >= 1, "and the simulator has it");

            await("the hold expires", () -> {
                sim.drainOut();
                return sim.pendingOut(p.player) == 0;
            });
            assertEquals(0, slotUpdates(p), "the release never re-enters sendPacket");
            assertEquals(1, released(p).stream().filter(s -> s instanceof SetSlotPacket slot && slot.windowId() == 0)
                    .count(), "the slot update reached the wire, and its frame decodes back to what was held");
        } finally {
            sim.setOutboundMillis(p.player, 0);
            MinecraftServer.getGlobalEventHandler().removeListener(hold);
            p.player.remove();
        }
    }

    private static int slotUpdates(FakePlayer p) {
        return p.sent(SetPlayerInventorySlotPacket.class).size()
                + (int) p.sent(SetSlotPacket.class).stream().filter(s -> s.windowId() == 0).count();
    }

    /** What the simulator wrote as framed bytes, read back off the wire - nothing else on the connection is. */
    private static List<ServerPacket> released(FakePlayer p) {
        List<ServerPacket> out = new ArrayList<>();
        for (SendablePacket sent : p.sent) {
            if (!(sent instanceof BufferedPacket framed)) continue;
            try {
                Result<ServerPacket> read = PacketReading.readServers(framed.buffer(), ConnectionState.PLAY,
                        MinecraftServer.getCompressionThreshold() > 0);
                if (read instanceof Result.Success<ServerPacket> ok) {
                    for (ParsedPacket<ServerPacket> one : ok.packets()) out.add(one.packet());
                }
            } catch (DataFormatException e) {
                throw new AssertionError("the simulator framed a packet the wire cannot read", e);
            }
        }
        return out;
    }
}
