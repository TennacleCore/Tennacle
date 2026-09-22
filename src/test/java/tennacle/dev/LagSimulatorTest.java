package tennacle.dev;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.network.packet.client.play.ClientPlayerPositionPacket;
import net.minestom.server.ServerFlag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reported from games: a second /lag kicked the player with "Too Many Packets". The inbound queue Minestom
 * re-feeds into is bounded and drains at a fixed rate per tick, so a backlog cannot be handed over in one go.
 */
class LagSimulatorTest extends HeadlessServerTest {

    private static int budget() {
        return Math.max(1, ServerFlag.PLAYER_PACKET_PER_TICK / 2);
    }

    private static PlayerPacketEvent move(Player player, int i) {
        return new PlayerPacketEvent(player, new ClientPlayerPositionPacket(new Vec(i, 70, 0), (byte) 1));
    }

    /** Lowering the delay makes the whole backlog eligible at once; it has to leave over several ticks. */
    @Test
    void aBacklogLeavesInSteps() {
        LagSimulator sim = new LagSimulator();
        FakePlayer p = FakePlayer.connect(instance, new Pos(900.5, 70, 900.5), "LagStep");
        try {
            sim.setDelay(p.player, 200);
            for (int i = 0; i < 300; i++) sim.onPacket(move(p.player, i));
            assertEquals(300, sim.pending(p.player), "all held while the delay is long");

            sim.setDelay(p.player, 1);
            for (int i = 300; i < 350; i++) sim.onPacket(move(p.player, i));

            sim.flush(); // the shortened pipe brings the whole backlog due at once
            assertEquals(350 - budget(), sim.pending(p.player), "one tick releases one budget, not the pile");
            sim.flush();
            assertEquals(350 - 2 * budget(), sim.pending(p.player), "and it keeps stepping");
        } finally {
            sim.setDelay(p.player, 0);
            p.player.remove();
        }
    }

    /** /lag 0 must not let a fresh packet past the ones still held: the server would see the player snap back. */
    @Test
    void nothingOvertakesAHeldPacket() {
        LagSimulator sim = new LagSimulator();
        FakePlayer p = FakePlayer.connect(instance, new Pos(910.5, 70, 900.5), "LagOrder");
        try {
            sim.setDelay(p.player, 5);
            sim.onPacket(move(p.player, 0));
            assertEquals(1, sim.pending(p.player));

            sim.setDelay(p.player, 0);
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
            sim.setDelay(p.player, 0);
            p.player.remove();
        }
    }
}
