package io.github.term4.archipelago.replay;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.play.EntityVelocityPacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A puppet TNT twin at rate 1 rides the VELOCITY wire: its client integrates each leg (the bounce arc
 *  renders), never a zero-velocity churn per tick (which stripped the integration and collapsed bounces
 *  to 20Hz steps). TNT carries no polyp save stamp, so its twin is always the visual puppet. */
class PuppetTntWireTest extends HeadlessServerTest {

    @org.junit.jupiter.api.BeforeAll
    static void channels() {
        io.github.term4.archipelago.replay.core.CoreChannels.register();
    }

    private static void tick() {
        MinecraftServer.getSchedulerManager().processTick();
        MinecraftServer.getSchedulerManager().processTickEnd();
    }

    @Test
    void rateOneBounceRidesTheVelocityWire() {
        Shard game = new Shard(instance);
        Recording rec = Replay.record(game);
        byte[] file;
        Entity tnt = new Entity(EntityType.TNT);
        try {
            tnt.setNoGravity(true);
            game.spawn(tnt, new Pos(20.5, 70, 20.5)).join();
            tick();
            // a hand-driven mmc18-style bounce: up, apex, down, ground, small rebound, rest
            double[] ys = {70.4, 70.7, 70.85, 70.7, 70.4, 70.0, 70.3, 70.45, 70.3, 70.0, 70.0, 70.0};
            for (int i = 0; i < ys.length; i++) {
                tnt.teleport(new Pos(20.5 + 0.3 * (i + 1), ys[i], 20.5)).join();
                tick();
            }
        } finally {
            file = rec.stop();
            tnt.remove();
        }

        Shard theater = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(23.5, 70, 20.5), "TntViewer");
        theater.join(viewer.player);
        Playback pb = Replay.play(theater, file);
        try {
            Entity twin = null;
            for (int i = 0; i < 100 && twin == null; i++) {
                tick();
                twin = pb.entity(0);
            }
            assertNotNull(twin, "the twin rose");
            for (int i = 0; i < 20 && twin.getInstance() == null; i++) tick();
            int id = twin.getEntityId();
            viewer.sent.clear();
            int moving = 0, zeroed = 0;
            for (int i = 0; i < 20; i++) {
                tick();
                for (SendablePacket sp : List.copyOf(viewer.sent)) {
                    var pk = SendablePacket.extractServerPacket(ConnectionState.PLAY, sp);
                    if (pk instanceof EntityVelocityPacket p && p.entityId() == id) {
                        if (p.velocity().length() > 1.0e-3) moving++;
                        else zeroed++;
                    }
                }
                viewer.sent.clear();
            }
            System.out.println("[tnt-wire] moving=" + moving + " zeroed=" + zeroed);
            assertTrue(moving >= 5, "the legs ride the velocity wire (saw " + moving + " redirects)");
            assertTrue(zeroed <= 2, "no per-tick zero-velocity churn (saw " + zeroed + ")");
        } finally {
            pb.halt();
            viewer.player.remove();
        }
    }
}
