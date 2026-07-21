package io.github.term4.archipelago.replay;

import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.mechanics.explosion.ExplosionSystem;
import io.github.term4.minestommechanics.presets.PrimedTnt;
import io.github.term4.minestommechanics.presets.mmc18.Explosion;
import io.github.term4.minestommechanics.presets.mmc18.Tnt;
import io.github.term4.minestommechanics.vri.DroppedItemEntity;
import io.github.term4.minestommechanics.world.MechanicsWorld;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.storage.EntityDescriptors;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.play.EntityPositionAndRotationPacket;
import net.minestom.server.network.packet.server.play.EntityPositionPacket;
import net.minestom.server.network.packet.server.play.EntityPositionSyncPacket;
import net.minestom.server.network.packet.server.play.EntityTeleportPacket;
import net.minestom.server.network.packet.server.play.EntityVelocityPacket;
import net.minestom.server.coordinate.Vec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.ReplayBridge;
import test.ShardBridge;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end: a REAL mmc18 TNT's landing bounce must survive record -> replay -> what the client renders. */
class TntBounceReplayTest extends HeadlessServerTest {

    private static ExplosionSystem explosions;

    @BeforeAll
    static void mechanics() {
        MinestomMechanics mm = MinestomMechanics.getInstance();
        mm.init();
        ShardBridge.install();
        ReplayBridge.install();
        // wire tests emulate specific generations: viewer names carry the split
        Replay.legacyViewers(p -> p.getUsername().contains("Legacy"));
        explosions = new ExplosionSystem(mm, Explosion.config());
        EntityDescriptors.describeEntity(e -> {
            var stamp = e.getTag(MechanicsWorld.ENTITY_SAVE);
            return stamp != null ? stamp.get() : null;
        });
        EntityDescriptors.reviveEntity("mm:item", DroppedItemEntity::fromSave);
        EntityDescriptors.reviveEntity("mm:tnt", d -> PrimedTnt.fromSave(explosions, d));
    }

    private static void tick() {
        MinecraftServer.getSchedulerManager().processTick();
        MinecraftServer.getSchedulerManager().processTickEnd();
    }

    @Test
    void rateOneLandingBounceRenders() {
        Shard game = new Shard(instance);
        Recording rec = Replay.record(game);
        byte[] file;
        boolean bounced = false;
        PrimedTnt tnt = null;
        try {
            tick();
            tnt = Tnt.spawn(explosions, instance, new BlockVec(30, 70, 30)); // falls to the 64 floor: a HARD landing
            game.transfer(tnt); // in-game the shard spawns it; the sampler only sees shard members
            for (int i = 0; i < 45; i++) {
                tnt.tick(0);
                tick();
                if (!bounced && tnt.isOnGround() && tnt.getVelocity().y() > 1.0) bounced = true;
            }
            assertTrue(bounced, "the RECORDED TNT really bounced (premise)");
        } finally {
            file = rec.stop();
            if (tnt != null) tnt.remove();
        }

        Shard theater = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(33.5, 66, 30.5), "BounceLegacy");
        theater.join(viewer.player);
        Playback pb = Replay.play(theater, file);
        try {
            Entity twin = null;
            for (int i = 0; i < 100 && twin == null; i++) {
                tick();
                twin = pb.entity(0);
            }
            assertNotNull(twin, "the twin rose");
            assertEquals(net.minestom.server.entity.EntityType.TNT, twin.getEntityType(), "a TNT puppet");
            for (int i = 0; i < 20 && twin.getInstance() == null; i++) tick();

            // emulate the vanilla CLIENT TNT (gravity, floor clip, the grounded x0.7/-0.5/x0.7 flip),
            // steered by whatever the twin's live hand wire sends - the rendered path on every client
            int id = twin.getEntityId();
            Pos clientPos = twin.getPosition();
            Vec clientVel = twin.getVelocity().div(20);
            double minY = 999, reboundPeak = -999, maxDrop = 0, maxCorrection = 0;
            int wirePackets = 0;
            viewer.sent.clear();
            for (int i = 0; i < 55 && !twin.isRemoved(); i++) { // a removed twin's stale velocity is not a path
                twin.tick(0); // the revived twin's own sim + wire (in-game the domain ticks it)
                tick();
                for (SendablePacket sp : List.copyOf(viewer.sent)) {
                    var pk = SendablePacket.extractServerPacket(ConnectionState.PLAY, sp);
                    if (pk instanceof EntityVelocityPacket p && p.entityId() == id) {
                        clientVel = p.velocity(); // blocks/tick on the wire
                        wirePackets++;
                    } else if (pk instanceof EntityPositionPacket p && p.entityId() == id) {
                        clientPos = clientPos.add(p.deltaX() / 4096.0, p.deltaY() / 4096.0, p.deltaZ() / 4096.0);
                    } else if (pk instanceof EntityPositionAndRotationPacket p && p.entityId() == id) {
                        clientPos = clientPos.add(p.deltaX() / 4096.0, p.deltaY() / 4096.0, p.deltaZ() / 4096.0);
                    } else if (pk instanceof EntityTeleportPacket p && p.entityId() == id) {
                        maxCorrection = Math.max(maxCorrection, clientPos.distance(Pos.fromPoint(p.position())));
                        clientPos = Pos.fromPoint(p.position());
                        wirePackets++;
                    } else if (pk instanceof EntityPositionSyncPacket p && p.entityId() == id) {
                        maxCorrection = Math.max(maxCorrection, clientPos.distance(Pos.fromPoint(p.position())));
                        clientPos = Pos.fromPoint(p.position());
                        wirePackets++;
                    }
                }
                viewer.sent.clear();
                double yBefore = clientPos.y();
                clientVel = clientVel.add(0, -0.04, 0);
                clientPos = clientPos.add(clientVel);
                boolean clientGround = clientPos.y() <= 64.0;
                if (clientGround) clientPos = clientPos.withY(64.0);
                clientVel = clientVel.mul(0.98);
                if (clientGround) clientVel = new Vec(clientVel.x() * 0.7, clientVel.y() * -0.5, clientVel.z() * 0.7);
                maxDrop = Math.max(maxDrop, yBefore - clientPos.y());
                if (clientPos.y() < minY) {
                    minY = clientPos.y();
                } else if (minY < 66) {
                    reboundPeak = Math.max(reboundPeak, clientPos.y());
                }
            }
            System.out.println("[bounce-wire] minY=" + String.format("%.2f", minY)
                    + " reboundPeak=" + String.format("%.2f", reboundPeak) + " wire=" + wirePackets
                    + " maxDrop=" + String.format("%.2f", maxDrop)
                    + " maxCorrection=" + String.format("%.2f", maxCorrection));
            assertTrue(reboundPeak > minY + 0.3,
                    "the landing bounce renders (min " + minY + ", rebound " + reboundPeak + ")");
            assertTrue(wirePackets >= 2, "the twin's live hand wire reaches theater viewers (saw " + wirePackets + ")");
            // a startup-lagged twin rides resync teleports mid-fall: the visible air-to-ground snap
            assertTrue(maxDrop < 0.95, "no teleport-sized downward step (saw " + maxDrop + ")");
            // the kick randomness lives in the DESCRIPTOR (state capture): the resim is deterministic, so
            // every absolute correction must land near where the client already is - off-path corrections
            // mean the revived state mismatched the track (the rubber-band signature)
            // a legacy client is fully SET-driven (its wire velocity is starved, so its sim holds
            // between sets, frame-lerped): "correction" here is one tick of track motion, not a snap
            assertTrue(maxCorrection < 1.0, "corrections stay on-path (saw " + maxCorrection + ")");
        } finally {
            pb.halt();
            viewer.player.remove();
        }
    }
    /** The in-game shape: a DOMAIN theater ticks the revived twin itself - its live sim must land AND
     *  rebound with no test-side ticking at all. */
    @Test
    void domainTheaterRebounds() {
        Shard game = new Shard(instance);
        Recording rec = Replay.record(game);
        byte[] file;
        boolean bounced = false;
        PrimedTnt tnt = null;
        try {
            tick();
            tnt = Tnt.spawn(explosions, instance, new BlockVec(50, 70, 50));
            game.transfer(tnt);
            for (int i = 0; i < 45; i++) {
                tnt.tick(0);
                tick();
                if (!bounced && tnt.isOnGround() && tnt.getVelocity().y() > 1.0) bounced = true;
            }
            assertTrue(bounced, "the RECORDED TNT really bounced (premise)");
        } finally {
            file = rec.stop();
            if (tnt != null) tnt.remove();
        }

        io.github.term4.archipelago.ticking.ExecutionDomain domain =
                io.github.term4.archipelago.ticking.ExecutionDomain.create();
        Shard theater = new Shard(instance, domain);
        Playback pb = Replay.play(theater, file);
        try {
            await("the twin rose", () -> pb.entity(0) != null);
            Entity twin = pb.entity(0);
            assertEquals(net.minestom.server.entity.EntityType.TNT, twin.getEntityType());
            double[] minY = {999};
            double[] rebound = {-999};
            await("the domain-ticked twin lands and rebounds", () -> {
                if (twin.isRemoved()) return rebound[0] > minY[0] + 0.3;
                double y = twin.getPosition().y();
                if (y < minY[0]) minY[0] = y;
                else if (minY[0] < 64.5) rebound[0] = Math.max(rebound[0], y);
                return rebound[0] > minY[0] + 0.3;
            });
        } finally {
            pb.halt();
            domain.retire();
        }
    }

    /** The domain shape at a fractional rate: the parked twin's legs must still land AND rebound. */
    @Test
    void domainTheaterReboundsAtHalfRate() {
        Shard game = new Shard(instance);
        Recording rec = Replay.record(game);
        byte[] file;
        boolean bounced = false;
        PrimedTnt tnt = null;
        try {
            tick();
            tnt = Tnt.spawn(explosions, instance, new BlockVec(90, 70, 90));
            game.transfer(tnt);
            for (int i = 0; i < 45; i++) {
                tnt.tick(0);
                tick();
                if (!bounced && tnt.isOnGround() && tnt.getVelocity().y() > 1.0) bounced = true;
            }
            assertTrue(bounced, "the RECORDED TNT really bounced (premise)");
        } finally {
            file = rec.stop();
            if (tnt != null) tnt.remove();
        }

        io.github.term4.archipelago.ticking.ExecutionDomain domain =
                io.github.term4.archipelago.ticking.ExecutionDomain.create();
        Shard theater = new Shard(instance, domain);
        Playback pb = Replay.play(theater, file);
        pb.rate(0.5);
        try {
            await("the twin rose", () -> pb.entity(0) != null);
            Entity twin = pb.entity(0);
            assertEquals(net.minestom.server.entity.EntityType.TNT, twin.getEntityType());
            double[] minY = {999};
            double[] rebound = {-999};
            await("the parked twin lands and rebounds at half rate", () -> {
                if (twin.isRemoved()) return rebound[0] > minY[0] + 0.25;
                double y = twin.getPosition().y();
                if (y < minY[0]) minY[0] = y;
                else if (minY[0] < 64.5) rebound[0] = Math.max(rebound[0], y);
                return rebound[0] > minY[0] + 0.25;
            });
        } finally {
            pb.halt();
            domain.retire();
        }
    }
    /** Fractional rates park the twin: the legs + per-tick re-aims must still carry the landing bounce. */
    @Test
    void fractionalBounceRenders() {
        Shard game = new Shard(instance);
        Recording rec = Replay.record(game);
        byte[] file;
        boolean bounced = false;
        PrimedTnt tnt = null;
        try {
            tick();
            tnt = Tnt.spawn(explosions, instance, new BlockVec(70, 70, 70));
            game.transfer(tnt);
            for (int i = 0; i < 45; i++) {
                tnt.tick(0);
                tick();
                if (!bounced && tnt.isOnGround() && tnt.getVelocity().y() > 1.0) bounced = true;
            }
            assertTrue(bounced, "the RECORDED TNT really bounced (premise)");
        } finally {
            file = rec.stop();
            if (tnt != null) tnt.remove();
        }

        Shard theater = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(73.5, 66, 70.5), "SlowBounceViewer");
        theater.join(viewer.player);
        Playback pb = Replay.play(theater, file);
        pb.rate(0.5);
        try {
            Entity twin = null;
            for (int i = 0; i < 100 && twin == null; i++) {
                tick();
                twin = pb.entity(0);
            }
            assertNotNull(twin, "the twin rose");
            for (int i = 0; i < 20 && twin.getInstance() == null; i++) tick();

            // the stand-in's client: NO_GRAVITY, velocity only from re-aims; the grounded flip still runs
            int id = twin.getEntityId();
            Pos clientPos = twin.getPosition();
            Vec clientVel = Vec.ZERO;
            double minY = 999, reboundPeak = -999;
            int moving = 0;
            viewer.sent.clear();
            for (int i = 0; i < 110 && !twin.isRemoved(); i++) {
                twin.tick(0);
                tick();
                for (SendablePacket sp : List.copyOf(viewer.sent)) {
                    var pk = SendablePacket.extractServerPacket(ConnectionState.PLAY, sp);
                    if (pk instanceof EntityVelocityPacket p && p.entityId() == id) {
                        clientVel = p.velocity();
                        if (p.velocity().length() > 1.0e-3) moving++;
                    } else if (pk instanceof EntityPositionPacket p && p.entityId() == id) {
                        clientPos = clientPos.add(p.deltaX() / 4096.0, p.deltaY() / 4096.0, p.deltaZ() / 4096.0);
                    } else if (pk instanceof EntityPositionAndRotationPacket p && p.entityId() == id) {
                        clientPos = clientPos.add(p.deltaX() / 4096.0, p.deltaY() / 4096.0, p.deltaZ() / 4096.0);
                    } else if (pk instanceof EntityTeleportPacket p && p.entityId() == id) {
                        clientPos = Pos.fromPoint(p.position());
                    } else if (pk instanceof EntityPositionSyncPacket p && p.entityId() == id) {
                        clientPos = Pos.fromPoint(p.position());
                    }
                }
                viewer.sent.clear();
                clientPos = clientPos.add(clientVel);
                boolean clientGround = clientPos.y() <= 64.0;
                if (clientGround) clientPos = clientPos.withY(64.0);
                clientVel = clientVel.mul(0.98);
                if (clientGround) clientVel = new Vec(clientVel.x() * 0.7, clientVel.y() * -0.5, clientVel.z() * 0.7);
                if (clientPos.y() < minY) {
                    minY = clientPos.y();
                } else if (minY < 64.5) {
                    reboundPeak = Math.max(reboundPeak, clientPos.y());
                }
            }
            System.out.println("[slow-bounce] minY=" + String.format("%.2f", minY)
                    + " reboundPeak=" + String.format("%.2f", reboundPeak) + " moving=" + moving);
            assertTrue(reboundPeak > minY + 0.25,
                    "the slowed landing bounce renders (min " + minY + ", rebound " + reboundPeak + ")");
        } finally {
            pb.halt();
            viewer.player.remove();
        }
    }
    /** The user's reproduction: an AIR spawn with a LONG fall reaches terminal (~2 b/t) - any phase error
     *  in the resim scales with speed and trips the resync band repeatedly (the rate-1 rubber-band). */
    @Test
    void longFallStaysOnPath() {
        Shard game = new Shard(instance);
        Recording rec = Replay.record(game);
        byte[] file;
        PrimedTnt tnt = null;
        try {
            tick();
            // mmc18 knobs but a LONG fuse: the stock 52 detonates mid-air on a 55-block drop
            tnt = PrimedTnt.spawn(explosions, instance, new BlockVec(110, 120, 110),
                    new PrimedTnt.Config(200, 4.0f, true, PrimedTnt.Wire.MINEMEN, true, null));
            game.transfer(tnt);
            for (int i = 0; i < 130; i++) {
                tnt.tick(0);
                tick();
            }
            assertTrue(tnt.isOnGround(), "the recorded TNT landed");
        } finally {
            file = rec.stop();
            if (tnt != null) tnt.remove();
        }

        Shard theater = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(113.5, 66, 110.5), "LongFallLegacy");
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
            Pos clientPos = twin.getPosition();
            Pos anchor = twin.getPosition(); // rel moves are server-anchored SETS, not adds to the sim
            Vec clientVel = twin.getVelocity().div(20);
            double maxCorrection = 0;
            viewer.sent.clear();
            for (int i = 0; i < 140 && !twin.isRemoved(); i++) {
                twin.tick(0);
                tick();
                for (SendablePacket sp : List.copyOf(viewer.sent)) {
                    var pk = SendablePacket.extractServerPacket(ConnectionState.PLAY, sp);
                    if (pk instanceof EntityVelocityPacket p && p.entityId() == id) {
                        clientVel = p.velocity();
                    } else if (pk instanceof EntityPositionPacket p && p.entityId() == id) {
                        anchor = anchor.add(p.deltaX() / 4096.0, p.deltaY() / 4096.0, p.deltaZ() / 4096.0);
                        clientPos = anchor;
                    } else if (pk instanceof EntityPositionAndRotationPacket p && p.entityId() == id) {
                        anchor = anchor.add(p.deltaX() / 4096.0, p.deltaY() / 4096.0, p.deltaZ() / 4096.0);
                        clientPos = anchor;
                    } else if (pk instanceof EntityTeleportPacket p && p.entityId() == id) {
                        maxCorrection = Math.max(maxCorrection, clientPos.distance(Pos.fromPoint(p.position())));
                        anchor = Pos.fromPoint(p.position());
                        clientPos = anchor;
                    } else if (pk instanceof EntityPositionSyncPacket p && p.entityId() == id) {
                        maxCorrection = Math.max(maxCorrection, clientPos.distance(Pos.fromPoint(p.position())));
                        anchor = Pos.fromPoint(p.position());
                        clientPos = anchor;
                    }
                }
                viewer.sent.clear();
                clientVel = clientVel.add(0, -0.04, 0);
                clientPos = clientPos.add(clientVel);
                if (clientPos.y() <= 64.0) clientPos = clientPos.withY(64.0);
                clientVel = clientVel.mul(0.98);
                if (clientPos.y() <= 64.0) clientVel = new Vec(clientVel.x() * 0.7, clientVel.y() * -0.5, clientVel.z() * 0.7);
            }
            System.out.println("[long-fall] maxCorrection=" + String.format("%.2f", maxCorrection));
            // a legacy client is fully SET-driven (starved wire velocity, sim holds between sets):
            // "correction" is one tick of track motion, bounded by terminal fall speed (~1.6)
            assertTrue(maxCorrection < 1.8, "a terminal-velocity fall stays on-path (saw " + maxCorrection + ")");
        } finally {
            pb.halt();
            viewer.player.remove();
        }
    }
    /** The domain shape of the long fall: no server-side step may exceed terminal velocity by a resync's
     *  margin - a teleport-sized jump IS the in-game rubber-band. */
    @Test
    void domainLongFallHasNoTeleportSteps() throws InterruptedException {
        Shard game = new Shard(instance);
        Recording rec = Replay.record(game);
        byte[] file;
        PrimedTnt tnt = null;
        try {
            tick();
            tnt = PrimedTnt.spawn(explosions, instance, new BlockVec(130, 120, 130),
                    new PrimedTnt.Config(200, 4.0f, true, PrimedTnt.Wire.MINEMEN, true, null));
            game.transfer(tnt);
            for (int i = 0; i < 130; i++) {
                tnt.tick(0);
                tick();
            }
            assertTrue(tnt.isOnGround(), "the recorded TNT landed");
        } finally {
            file = rec.stop();
            if (tnt != null) tnt.remove();
        }

        io.github.term4.archipelago.ticking.ExecutionDomain domain =
                io.github.term4.archipelago.ticking.ExecutionDomain.create();
        Shard theater = new Shard(instance, domain);
        Playback pb = Replay.play(theater, file);
        try {
            await("the twin rose", () -> pb.entity(0) != null);
            Entity twin = pb.entity(0);
            double maxStep = 0;
            double lastY = twin.getPosition().y();
            long deadline = System.currentTimeMillis() + 12_000;
            while (System.currentTimeMillis() < deadline && !twin.isRemoved()
                    && !(twin.getPosition().y() <= 64.5 && Math.abs(twin.getVelocity().y()) < 0.5)) {
                double y = twin.getPosition().y();
                if (y != lastY) {
                    maxStep = Math.max(maxStep, lastY - y); // downward jumps only
                    lastY = y;
                }
                Thread.sleep(1);
            }
            System.out.println("[domain-long-fall] maxStep=" + String.format("%.2f", maxStep));
            assertTrue(maxStep < 2.6, "no teleport-sized server step mid-fall (saw " + maxStep + ")");
        } finally {
            pb.halt();
            domain.retire();
        }
    }
}
