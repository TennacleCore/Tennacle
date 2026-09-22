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
import net.minestom.server.network.packet.client.play.ClientPlayerPositionPacket;
import net.minestom.server.network.packet.server.play.UpdateHealthPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reported from games: a second /lag kicked the player with "Too Many Packets". The inbound queue Minestom
 * re-feeds into is bounded and drains at a fixed rate per tick, so a backlog cannot be handed over in one go.
 * The outbound side rides the write thread instead, which is what lets a ping land off a tick boundary.
 */
class LagSimulatorTest extends HeadlessServerTest {

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
}
