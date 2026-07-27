package io.github.term4.archipelago.replay;

import io.github.term4.polyp.Polyp;
import io.github.term4.polyp.mechanics.projectile.ProjectileSnapshot;
import io.github.term4.polyp.mechanics.projectile.ProjectileSystem;
import io.github.term4.polyp.mechanics.projectile.entities.ProjectileEntity;
import io.github.term4.polyp.mechanics.projectile.types.Snowball;
import io.github.term4.polyp.presets.vanilla18.Vanilla18;
import io.github.term4.polyp.entity.DroppedItemEntity;
import io.github.term4.polyp.world.MechanicsWorld;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.storage.EntityDescriptors;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.DestroyEntitiesPacket;
import net.minestom.server.network.packet.server.play.EntityPositionAndRotationPacket;
import net.minestom.server.network.packet.server.play.EntityPositionSyncPacket;
import net.minestom.server.network.packet.server.play.EntityPositionPacket;
import net.minestom.server.network.packet.server.play.EntityTeleportPacket;
import net.minestom.server.network.packet.server.play.EntityVelocityPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import test.ReplayBridge;
import test.ShardBridge;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wire-level check: what a viewer actually receives for a replayed polyp projectile at 0.25x. */
class SlowMoWireTest extends HeadlessServerTest {

    private static Polyp polyp;
    private static ProjectileSystem projectiles;

    @BeforeAll
    static void mechanics() {
        polyp = Polyp.getInstance();
        polyp.init();
        ShardBridge.install();
        ReplayBridge.install();
        // wire tests emulate specific generations: viewer names carry the split
        Replay.legacyViewers(p -> p.getUsername().contains("Legacy"));
        projectiles = new ProjectileSystem(polyp, Vanilla18.projectiles());
        EntityDescriptors.describeEntity(e -> {
            var stamp = e.getTag(MechanicsWorld.ENTITY_SAVE);
            return stamp != null ? stamp.get() : null;
        });
        EntityDescriptors.reviveEntity("polyp:item", DroppedItemEntity::fromSave);
        EntityDescriptors.reviveEntity("polyp:projectile", projectiles::fromSave);
    }

    private static void tick() {
        MinecraftServer.getSchedulerManager().processTick();
        MinecraftServer.getSchedulerManager().processTickEnd();
    }

    @Test
    void quarterRateProjectileWireIsSmooth() {
        Shard game = new Shard(instance);
        FakePlayer thrower = FakePlayer.connect(instance, new Pos(8.5, 70, 8.5), "WireThrower");
        game.join(thrower.player);
        Recording rec = Replay.record(game);
        byte[] file;
        ProjectileEntity snowball = null;
        try {
            tick();
            snowball = projectiles.launch(ProjectileSnapshot.of(thrower.player, Snowball.INSTANCE)
                    .withConfig(Vanilla18.projectiles()));
            assertNotNull(snowball);
            MinecraftServer.getSchedulerManager().processTickEnd();
            long time = System.currentTimeMillis();
            for (int i = 0; i < 10; i++) { // manual physics (headless never ticks entities): it flies + records
                snowball.tick(time + i * 50L);
                tick();
            }
        } finally {
            file = rec.stop();
            if (snowball != null) snowball.remove();
            game.leave(thrower.player);
            thrower.player.remove();
        }

        Shard theater = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(8.5, 70, 8.5), "WireLegacy");
        theater.join(viewer.player);
        Playback pb = Replay.play(theater, file);
        pb.rate(0.25);
        try {
            Entity twin = null;
            for (int i = 0; i < 100 && twin == null; i++) {
                tick();
                twin = pb.entity(0);
            }
            assertNotNull(twin, "the twin rose");
            assertTrue(twin.hasNoGravity(), "parked at track time");

            int id = twin.getEntityId();
            // reconstruct what the CLIENT renders: rel moves accumulate the SERVER anchor and SET the
            // client onto it (both generations - no interpolation on projectiles), while velocity keeps
            // its own no-gravity sim (x 0.99 drag) integrating between packets. Under MIXED both streams
            // arrive per tick and must not double-apply
            Pos clientPos = twin.getPosition();
            Pos anchor = twin.getPosition();
            net.minestom.server.coordinate.Vec clientVel = net.minestom.server.coordinate.Vec.ZERO;
            double maxStep = 0, minMovingStep = Double.MAX_VALUE;
            StringBuilder log = new StringBuilder();
            long time = System.currentTimeMillis();
            for (int t = 0; t < 32; t++) {
                viewer.sent.clear();
                twin.tick(time + t * 50L); // in-game the domain clock does this
                tick();
                Pos before = clientPos;
                StringBuilder line = new StringBuilder();
                for (SendablePacket sp : List.copyOf(viewer.sent)) {
                    var pk = SendablePacket.extractServerPacket(ConnectionState.PLAY, sp);
                    if (pk instanceof EntityPositionPacket p && p.entityId() == id) {
                        anchor = anchor.add(p.deltaX() / 4096.0, p.deltaY() / 4096.0, p.deltaZ() / 4096.0);
                        clientPos = anchor;
                        line.append(" rel");
                    } else if (pk instanceof EntityPositionAndRotationPacket p && p.entityId() == id) {
                        anchor = anchor.add(p.deltaX() / 4096.0, p.deltaY() / 4096.0, p.deltaZ() / 4096.0);
                        clientPos = anchor;
                        line.append(" relrot");
                    } else if (pk instanceof EntityTeleportPacket p && p.entityId() == id) {
                        anchor = p.position();
                        clientPos = anchor;
                        line.append(" TELEPORT");
                    } else if (pk instanceof EntityPositionSyncPacket p && p.entityId() == id) {
                        anchor = new Pos(p.position().x(), p.position().y(), p.position().z());
                        clientPos = anchor;
                        line.append(" SYNC");
                    } else if (pk instanceof EntityVelocityPacket p && p.entityId() == id) {
                        clientVel = p.velocity(); // the packet Vec is already blocks/tick (LP_VECTOR3 wire)
                        line.append(" VEL=").append(String.format("%.3f", clientVel.length()));
                    }
                }
                clientPos = clientPos.add(clientVel);
                clientVel = clientVel.mul(0.99); // the client's own drag
                double step = clientPos.distance(before);
                if (t >= 6) { // warmup: spawn + first leg
                    maxStep = Math.max(maxStep, step);
                    if (step > 1.0e-3) minMovingStep = Math.min(minMovingStep, step);
                }
                log.append("t").append(t).append(": step=").append(String.format("%.3f", step))
                        .append(line).append("\n");
            }
            System.out.println("[slowmo-wire] client-side step range " + String.format("%.3f..%.3f", minMovingStep, maxStep)
                    + "\n" + log);
            // the recorded snowball flew ~1.5 b/t; the client must render ~rate x that, EVERY tick, uniformly
            assertTrue(maxStep < 0.6, "client step too large: " + maxStep);
            assertTrue(minMovingStep != Double.MAX_VALUE && maxStep / minMovingStep < 1.35,
                    "client motion pulses: " + String.format("%.3f..%.3f", minMovingStep, maxStep));
        } finally {
            pb.halt();
            viewer.player.remove();
        }
    }

    private static double sq(double v) {
        return v * v;
    }
    /** A slow-mo impact must despawn AT the wall - not a leg short of it. */
    @Test
    void slowMoImpactDespawnsAtTheWall() {
        for (int x = 5; x <= 12; x++) {
            for (int y = 68; y <= 74; y++) instance.setBlock(x, y, 74, Block.STONE);
        }
        Shard game = new Shard(instance);
        FakePlayer thrower = FakePlayer.connect(instance, new Pos(8.5, 70, 68.5), "ImpactThrower");
        game.join(thrower.player);
        Recording rec = Replay.record(game);
        byte[] file;
        ProjectileEntity ball = null;
        double impactZ = 0;
        try {
            tick();
            ball = projectiles.launch(ProjectileSnapshot.of(thrower.player, Snowball.INSTANCE)
                    .withConfig(Vanilla18.projectiles()));
            assertNotNull(ball);
            MinecraftServer.getSchedulerManager().processTickEnd();
            long time = System.currentTimeMillis();
            for (int i = 0; i < 20; i++) {
                if (!ball.isRemoved()) {
                    ball.tick(time + i * 50L);
                    impactZ = ball.getPosition().z();
                }
                tick();
            }
            assertTrue(ball.isRemoved(), "the recorded snowball hit the wall");
        } finally {
            file = rec.stop();
            game.leave(thrower.player);
            thrower.player.remove();
        }

        Shard theater = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(8.5, 70, 68.5), "ImpactLegacy");
        theater.join(viewer.player);
        Playback pb = Replay.play(theater, file);
        pb.rate(0.25);
        try {
            Entity twin = null;
            for (int i = 0; i < 100 && twin == null; i++) {
                tick();
                twin = pb.entity(0);
            }
            assertNotNull(twin, "the twin rose");
            int id = twin.getEntityId();
            Pos clientPos = twin.getPosition();
            net.minestom.server.coordinate.Vec clientVel = net.minestom.server.coordinate.Vec.ZERO;
            // a 1.8 client in parallel: NO NoGravity flag - its own gravity runs every tick, so any
            // packet gap at rest sinks the projectile (the freeze-sag-snap impact report)
            double legacyY = clientPos.y(), legacyVy = 0, legacyImpactY = 0;
            Double despawnAtZ = null, despawnLegacyY = null;
            long time = System.currentTimeMillis();
            for (int t = 0; t < 140 && despawnAtZ == null; t++) {
                if (!twin.isRemoved()) twin.tick(time + t * 50L);
                tick();
                for (SendablePacket sp : List.copyOf(viewer.sent)) {
                    var pk = SendablePacket.extractServerPacket(ConnectionState.PLAY, sp);
                    if (pk instanceof EntityVelocityPacket p && p.entityId() == id) {
                        clientVel = p.velocity();
                        legacyVy = p.velocity().y();
                    } else if (pk instanceof EntityPositionPacket p && p.entityId() == id) {
                        clientPos = clientPos.add(p.deltaX() / 4096.0, p.deltaY() / 4096.0, p.deltaZ() / 4096.0);
                        legacyY += p.deltaY() / 4096.0;
                    } else if (pk instanceof EntityPositionAndRotationPacket p && p.entityId() == id) {
                        clientPos = clientPos.add(p.deltaX() / 4096.0, p.deltaY() / 4096.0, p.deltaZ() / 4096.0);
                        legacyY += p.deltaY() / 4096.0;
                    } else if (pk instanceof EntityTeleportPacket p && p.entityId() == id) {
                        clientPos = Pos.fromPoint(p.position());
                        legacyY = p.position().y();
                        legacyImpactY = p.position().y();
                    } else if (pk instanceof EntityPositionSyncPacket p && p.entityId() == id) {
                        clientPos = Pos.fromPoint(p.position());
                        legacyY = p.position().y();
                        legacyImpactY = p.position().y();
                    } else if (pk instanceof DestroyEntitiesPacket p && p.entityIds().contains(id)) {
                        despawnAtZ = clientPos.z();
                        despawnLegacyY = legacyY;
                    }
                }
                viewer.sent.clear();
                clientPos = clientPos.add(clientVel);
                clientVel = clientVel.mul(0.99);
                legacyY += legacyVy;
                legacyVy = (legacyVy - 0.03) * 0.99; // 1.8 snowball: gravity always
            }
            assertNotNull(despawnAtZ, "the twin despawned");
            System.out.println("[impact-wire] despawn client z=" + String.format("%.2f", despawnAtZ)
                    + " impact z=" + String.format("%.2f", impactZ)
                    + " legacyY drift=" + String.format("%.2f", Math.abs(despawnLegacyY - legacyImpactY)));
            assertTrue(Math.abs(despawnAtZ - impactZ) < 0.4,
                    "despawns AT the wall (client z=" + despawnAtZ + " vs impact " + impactZ + ")");
            // the held-removal per-tick pins re-anchor the 1.8 client between its gravity ticks
            assertTrue(Math.abs(despawnLegacyY - legacyImpactY) < 0.15,
                    "a 1.8 viewer's projectile never sags during the impact hold (drift "
                            + Math.abs(despawnLegacyY - legacyImpactY) + ")");
        } finally {
            pb.halt();
            viewer.player.remove();
        }
    }
    /** The HYPIXEL preset broadcasts projectile velocity per tick live (velocitySyncInterval > 0) - the
     *  vanilla18-only harness never saw that writer fight the applier's legs. A parked twin must have ONE
     *  velocity author: every VEL on the wire is the applier's scaled leg, never polyp's internal state. */
    @Test
    void hypixelPresetTwinHasOneVelocityAuthor() {
        Shard game = new Shard(instance);
        FakePlayer thrower = FakePlayer.connect(instance, new Pos(28.5, 70, 8.5), "HypixelThrower");
        game.join(thrower.player);
        Recording rec = Replay.record(game);
        byte[] file;
        ProjectileEntity ball = null;
        try {
            tick();
            ball = projectiles.launch(ProjectileSnapshot.of(thrower.player, Snowball.INSTANCE)
                    .withConfig(io.github.term4.polyp.presets.hypixel.Projectiles.config()));
            assertNotNull(ball);
            MinecraftServer.getSchedulerManager().processTickEnd();
            long time = System.currentTimeMillis();
            for (int i = 0; i < 10; i++) {
                if (!ball.isRemoved()) ball.tick(time + i * 50L);
                tick();
            }
        } finally {
            file = rec.stop();
            if (ball != null && !ball.isRemoved()) ball.remove();
            game.leave(thrower.player);
            thrower.player.remove();
        }

        Shard theater = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(28.5, 70, 8.5), "HypixelViewer");
        theater.join(viewer.player);
        Playback pb = Replay.play(theater, file);
        pb.rate(0.25);
        try {
            Entity twin = null;
            for (int i = 0; i < 100 && twin == null; i++) {
                tick();
                twin = pb.entity(0);
            }
            assertNotNull(twin, "the twin rose");
            for (int i = 0; i < 20 && twin.getInstance() == null; i++) tick();
            viewer.sent.clear();
            int id = twin.getEntityId();
            double maxVel = 0;
            long time = System.currentTimeMillis();
            for (int t = 0; t < 24 && !twin.isRemoved(); t++) {
                twin.tick(time + t * 50L);
                tick();
                for (SendablePacket sp : List.copyOf(viewer.sent)) {
                    var pk = SendablePacket.extractServerPacket(ConnectionState.PLAY, sp);
                    if (pk instanceof EntityVelocityPacket p && p.entityId() == id) {
                        maxVel = Math.max(maxVel, p.velocity().length());
                    }
                }
                viewer.sent.clear();
            }
            System.out.println("[hypixel-wire] maxVel=" + String.format("%.3f", maxVel));
            // recorded snowball ~1.5 b/t; every broadcast must be the SCALED leg (~0.375), never polyp's
            // internal full-speed state (the captured chaotic-legs bug)
            assertTrue(maxVel > 0.01 && maxVel < 0.8, "one scaled velocity author (max " + maxVel + ")");
        } finally {
            pb.halt();
            viewer.player.remove();
        }
    }

    /** The captured #18: at 0.1x both snowballs and fireballs flew uniform ~0.13 steps then LEAPED the
     *  final recorded tick (~1.4 blocks) onto the impact point. The legacy abs stream must stay paced
     *  through the collision. */
    @org.junit.jupiter.api.Test
    void slowMoImpactHasNoTerminalLeap() {
        Shard game = new Shard(instance);
        Entity ball = new Entity(net.minestom.server.entity.EntityType.SNOWBALL);
        Recording rec = Replay.record(game);
        byte[] file;
        try {
            game.spawn(ball, new Pos(8.5, 70, 88.5)).join();
            tick(); // sighting
            // the live shape: sampler ops 1.5/tick WITH a mid-flight hole (real tracks skip ticks),
            // the final op AT the impact, then the impact-time removal at the SAME position and tick
            // (the recorded duplicate)
            double z = 88.5;
            for (int i = 1; i <= 2; i++) {
                z += 1.5;
                rec.emit(io.github.term4.archipelago.replay.core.Channels.ENTITY_MOVES,
                        new io.github.term4.archipelago.replay.core.Channels.EntityMove(0, 8.5, 70, z, 0, 0));
                tick();
            }
            tick();
            tick(); // the hole: two silent recorded ticks mid-flight
            z += 4.5;
            rec.emit(io.github.term4.archipelago.replay.core.Channels.ENTITY_MOVES,
                    new io.github.term4.archipelago.replay.core.Channels.EntityMove(0, 8.5, 70, z, 0, 0));
            tick();
            z += 1.5;
            rec.emit(io.github.term4.archipelago.replay.core.Channels.ENTITY_MOVES,
                    new io.github.term4.archipelago.replay.core.Channels.EntityMove(0, 8.5, 70, z, 0, 0));
            tick();
            rec.remove(ball, new Pos(8.5, 70, z), "");
            tick();
        } finally {
            file = rec.stop();
            ball.remove();
        }

        Shard theater = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(8.5, 70, 88.5), "LeapLegacy");
        theater.join(viewer.player);
        Playback pb = Replay.play(theater, file);
        pb.rate(0.1);
        try {
            Entity twin = null;
            for (int i = 0; i < 120 && twin == null; i++) {
                tick();
                twin = pb.entity(0);
            }
            assertNotNull(twin, "the twin rose");
            int id = twin.getEntityId();
            Pos last = null;
            double flightStep = 0, maxStep = 0;
            StringBuilder log = new StringBuilder();
            long time = System.currentTimeMillis();
            for (int t = 0; t < 400 && !twin.isRemoved(); t++) {
                twin.tick(time + t * 50L);
                tick();
                for (SendablePacket sp : List.copyOf(viewer.sent)) {
                    var pk = SendablePacket.extractServerPacket(ConnectionState.PLAY, sp);
                    Pos at = null;
                    if (pk instanceof EntityPositionSyncPacket p && p.entityId() == id) {
                        at = new Pos(p.position().x(), p.position().y(), p.position().z());
                    } else if (pk instanceof EntityTeleportPacket p && p.entityId() == id) {
                        at = Pos.fromPoint(p.position());
                    }
                    if (at == null) continue;
                    if (last != null) {
                        double step = at.distance(last);
                        if (step > 1.0e-3) {
                            flightStep = Math.max(flightStep, Math.min(step, 0.5)); // typical pace, leap-capped
                            maxStep = Math.max(maxStep, step);
                            log.append(String.format("t%d %.3f  ", t, step));
                        }
                    }
                    last = at;
                }
                viewer.sent.clear();
            }
            System.out.println("[leap] maxStep=" + String.format("%.3f", maxStep)
                    + " lastAbs=" + last + " steps: " + log);
            assertTrue(maxStep > 0, "abs stream observed");
            assertTrue(maxStep <= flightStep * 3 + 0.05,
                    "the collision approach stays paced (max " + maxStep + " vs flight " + flightStep + ")");
            assertNotNull(last);
            assertTrue(last.distance(new Pos(8.5, 70, 97.5)) < 0.2,
                    "the client's final position IS the impact (saw " + last + ")");
        } finally {
            pb.halt();
            viewer.player.remove();
        }
    }
}
