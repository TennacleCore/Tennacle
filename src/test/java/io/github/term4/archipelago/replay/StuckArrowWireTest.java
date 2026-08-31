package io.github.term4.archipelago.replay;

import io.github.term4.polyp.Polyp;
import io.github.term4.polyp.mechanics.projectile.ProjectileSnapshot;
import io.github.term4.polyp.mechanics.projectile.ProjectileSystem;
import io.github.term4.polyp.mechanics.projectile.entities.ProjectileEntity;
import io.github.term4.polyp.mechanics.projectile.types.Arrow;
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
import net.minestom.server.entity.metadata.projectile.AbstractArrowMeta;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket;
import net.minestom.server.network.packet.server.play.EntityPositionAndRotationPacket;
import net.minestom.server.network.packet.server.play.EntityPositionSyncPacket;
import net.minestom.server.network.packet.server.play.EntityRotationPacket;
import net.minestom.server.network.packet.server.play.EntityTeleportPacket;
import net.minestom.server.network.packet.server.play.EntityVelocityPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.bridge.ReplayBridge;
import tennacle.bridge.ShardBridge;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wire-level check: a rate-1 replayed STUCK arrow must sit visually still - one orientation, no churn. */
class StuckArrowWireTest extends HeadlessServerTest {

    private static ProjectileSystem projectiles;

    @BeforeAll
    static void mechanics() {
        Polyp polyp = Polyp.getInstance();
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
    void rateOneStuckArrowSitsStill() {
        for (int x = 5; x <= 12; x++) {
            for (int y = 68; y <= 74; y++) instance.setBlock(x, y, 14, Block.STONE);
        }
        Shard game = new Shard(instance);
        // pitched DOWN into the wall: the stuck view must be a real flight orientation, not a 0/0 default
        FakePlayer thrower = FakePlayer.connect(instance, new Pos(8.5, 72, 8.5, 0, 30), "StuckThrower");
        game.join(thrower.player);
        Recording rec = Replay.record(game);
        byte[] file;
        ProjectileEntity arrow = null;
        try {
            tick();
            arrow = projectiles.launch(ProjectileSnapshot.of(thrower.player, Arrow.INSTANCE)
                    .withConfig(Vanilla18.projectiles()));
            assertNotNull(arrow);
            MinecraftServer.getSchedulerManager().processTickEnd();
            long time = System.currentTimeMillis();
            for (int i = 0; i < 40; i++) { // fly, stick, then REST on the recorded track
                arrow.tick(time + i * 50L);
                tick();
            }
            assertTrue(arrow.isStuck(), "the recorded arrow stuck in the wall");
        } finally {
            file = rec.stop();
            if (arrow != null) arrow.remove();
            game.leave(thrower.player);
            thrower.player.remove();
        }

        Shard theater = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(8.5, 70, 8.5), "StuckViewer");
        theater.join(viewer.player);
        Playback pb = Replay.play(theater, file);
        try {
            Entity twin = null;
            for (int i = 0; i < 100 && twin == null; i++) {
                tick();
                twin = pb.entity(0);
            }
            assertNotNull(twin, "the twin rose");
            long time = System.currentTimeMillis();
            int t = 0;
            // parked twins never run polyp's stick transitions: the rest contract is the arrival's
            // IN_GROUND freeze at the recorded stick point
            for (; t < 80 && !(twin.getEntityMeta() instanceof
                    net.minestom.server.entity.metadata.projectile.AbstractArrowMeta m && m.isInGround()); t++) {
                twin.tick(time + t * 50L);
                tick();
            }
            assertTrue(twin.getEntityMeta() instanceof
                    net.minestom.server.entity.metadata.projectile.AbstractArrowMeta m && m.isInGround(),
                    "the twin froze stuck");

            // the REST window: whatever reaches the viewer now is churn - collect every rotation-bearing
            // packet and every distinct view it stamps
            viewer.sent.clear();
            int id = twin.getEntityId();
            Set<String> views = new LinkedHashSet<>();
            int viewPackets = 0, metaPackets = 0, velocityPackets = 0;
            StringBuilder log = new StringBuilder();
            for (int i = 0; i < 40; i++) {
                twin.tick(time + (t + i) * 50L);
                tick();
                for (SendablePacket sp : List.copyOf(viewer.sent)) {
                    var pk = SendablePacket.extractServerPacket(ConnectionState.PLAY, sp);
                    String view = null;
                    if (pk instanceof EntityTeleportPacket p && p.entityId() == id) {
                        view = String.format("TP %.0f/%.0f", p.position().yaw(), p.position().pitch());
                    } else if (pk instanceof EntityPositionSyncPacket p && p.entityId() == id) {
                        view = String.format("SYNC %.0f/%.0f", p.yaw(), p.pitch());
                    } else if (pk instanceof EntityPositionAndRotationPacket p && p.entityId() == id) {
                        view = String.format("RELROT %.0f/%.0f", (float) p.yaw(), (float) p.pitch());
                    } else if (pk instanceof EntityRotationPacket p && p.entityId() == id) {
                        view = String.format("ROT %.0f/%.0f", p.yaw(), p.pitch());
                    } else if (pk instanceof EntityMetaDataPacket p && p.entityId() == id) {
                        metaPackets++;
                        log.append("t").append(i).append(": META\n");
                    } else if (pk instanceof EntityVelocityPacket p && p.entityId() == id
                            && p.velocity().length() > 1.0e-3) {
                        velocityPackets++;
                        log.append("t").append(i).append(": VEL ").append(p.velocity()).append("\n");
                    }
                    if (view != null) {
                        viewPackets++;
                        views.add(view.substring(view.indexOf(' ') + 1));
                        log.append("t").append(i).append(": ").append(view).append("\n");
                    }
                }
                viewer.sent.clear();
            }
            System.out.println("[stuck-wire] viewPackets=" + viewPackets + " distinctViews=" + views
                    + " meta=" + metaPackets + " vel=" + velocityPackets + "\n" + log);
            assertTrue(views.size() <= 1, "one orientation only at rest (saw " + views + ")");
            assertTrue(velocityPackets == 0, "no velocity churn at rest");
        } finally {
            pb.halt();
            viewer.player.remove();
        }
    }
    /** At fractional rates the twin is a PHANTOM (it never polyp-sticks): its rest must still advertise
     *  IN_GROUND, or modern clients aim the arrow at its zero velocity - the fully-sideways look. */
    @Test
    void fractionalRestAdvertisesInGround() {
        for (int x = 5; x <= 12; x++) {
            for (int y = 68; y <= 74; y++) instance.setBlock(x, y, 44, Block.STONE);
        }
        Shard game = new Shard(instance);
        FakePlayer thrower = FakePlayer.connect(instance, new Pos(8.5, 72, 38.5, 0, 30), "PhantomThrower");
        game.join(thrower.player);
        Recording rec = Replay.record(game);
        byte[] file;
        ProjectileEntity arrow = null;
        try {
            tick();
            arrow = projectiles.launch(ProjectileSnapshot.of(thrower.player, Arrow.INSTANCE)
                    .withConfig(Vanilla18.projectiles()));
            assertNotNull(arrow);
            MinecraftServer.getSchedulerManager().processTickEnd();
            long time = System.currentTimeMillis();
            for (int i = 0; i < 40; i++) {
                arrow.tick(time + i * 50L);
                tick();
            }
            assertTrue(arrow.isStuck(), "the recorded arrow stuck in the wall");
        } finally {
            file = rec.stop();
            if (arrow != null) arrow.remove();
            game.leave(thrower.player);
            thrower.player.remove();
        }

        Shard theater = new Shard(instance);
        Playback pb = Replay.play(theater, file);
        pb.rate(0.5);
        try {
            Entity twin = null;
            for (int i = 0; i < 100 && twin == null; i++) {
                tick();
                twin = pb.entity(0);
            }
            assertNotNull(twin, "the twin rose");
            long time = System.currentTimeMillis();
            for (int i = 0; i < 120; i++) { // fly the halved legs, arrive, rest
                if (!twin.isRemoved()) twin.tick(time + i * 50L);
                tick();
            }
            assertFalse(twin instanceof ProjectileEntity, "puppet-only: no polyp sim to stick");
            assertTrue(((AbstractArrowMeta) twin.getEntityMeta()).isInGround(),
                    "rest advertises IN_GROUND (a zero-velocity arrow aims sideways without it)");
        } finally {
            pb.halt();
        }
    }
    /** Reversed from its stuck rest, the arrow must UNFREEZE and fly velocity legs backward - frozen it
     *  glides the whole arc on 20Hz relative moves (the reverse-arrow chop). */
    @Test
    void reverseFlightUnfreezesAndFliesLegs() {
        for (int x = 5; x <= 12; x++) {
            for (int y = 68; y <= 74; y++) instance.setBlock(x, y, 44, Block.STONE);
        }
        Shard game = new Shard(instance);
        FakePlayer thrower = FakePlayer.connect(instance, new Pos(8.5, 72, 38.5, 0, 10), "ReverseThrower");
        game.join(thrower.player);
        Recording rec = Replay.record(game);
        byte[] file;
        ProjectileEntity arrow = null;
        try {
            tick();
            arrow = projectiles.launch(ProjectileSnapshot.of(thrower.player, Arrow.INSTANCE)
                    .withConfig(Vanilla18.projectiles()));
            assertNotNull(arrow);
            MinecraftServer.getSchedulerManager().processTickEnd();
            long time = System.currentTimeMillis();
            for (int i = 0; i < 30; i++) {
                arrow.tick(time + i * 50L);
                tick();
            }
            assertTrue(arrow.isStuck(), "the recorded arrow stuck (premise)");
        } finally {
            file = rec.stop();
            if (arrow != null) arrow.remove();
            game.leave(thrower.player);
            thrower.player.remove();
        }

        Shard theater = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(8.5, 70, 38.5), "ReverseViewer");
        FakePlayer legacy = FakePlayer.connect(instance, new Pos(6.5, 70, 38.5), "ReverseLegacy");
        io.github.term4.polyp.Polyp.getInstance().clientInfo()
                .setProxyDetails(legacy.player, "{\"version\":47}");
        theater.join(viewer.player);
        theater.join(legacy.player);
        Playback pb = Replay.play(theater, file);
        try {
            Entity twin = null;
            long time = System.currentTimeMillis();
            int t = 0;
            for (; t < 120; t++) { // play to the stuck rest
                tick();
                if (twin == null) twin = pb.entity(0);
                if (twin != null && !twin.isRemoved()) twin.tick(time + t * 50L);
                if (twin != null && twin.getEntityMeta() instanceof
                        net.minestom.server.entity.metadata.projectile.AbstractArrowMeta m && m.isInGround()) break;
            }
            assertNotNull(twin, "the twin rose");
            assertTrue(twin.getEntityMeta() instanceof
                    net.minestom.server.entity.metadata.projectile.AbstractArrowMeta m && m.isInGround(),
                    "resting frozen (premise)");

            pb.rate(0.5);
            pb.reverse(true);
            int id = twin.getEntityId();
            viewer.sent.clear();
            legacy.sent.clear();
            int flightVels = 0, legacyPositions = 0;
            boolean unfroze = false;
            for (int i = 0; i < 60 && !twin.isRemoved(); i++) {
                twin.tick(time + (t + 1 + i) * 50L);
                tick();
                for (SendablePacket sp : List.copyOf(viewer.sent)) {
                    var pk = SendablePacket.extractServerPacket(ConnectionState.PLAY, sp);
                    if (pk instanceof EntityVelocityPacket p && p.entityId() == id
                            && p.velocity().length() > 0.02) flightVels++;
                }
                for (SendablePacket sp : List.copyOf(legacy.sent)) {
                    var pk = SendablePacket.extractServerPacket(ConnectionState.PLAY, sp);
                    if ((pk instanceof net.minestom.server.network.packet.server.play.EntityPositionSyncPacket p
                            && p.entityId() == id)
                            || (pk instanceof net.minestom.server.network.packet.server.play.EntityTeleportPacket r
                            && r.entityId() == id)) legacyPositions++;
                }
                viewer.sent.clear();
                legacy.sent.clear();
                if (twin.getEntityMeta() instanceof
                        net.minestom.server.entity.metadata.projectile.AbstractArrowMeta m && !m.isInGround()) {
                    unfroze = true;
                }
            }
            System.out.println("[reverse-arrow] flightVels=" + flightVels + " unfroze=" + unfroze
                    + " legacyPositions=" + legacyPositions);
            assertTrue(unfroze, "the reverse flight unfreezes the arrow");
            // the recorded arc is only ~3 ticks (speed 3.0, wall 5.5 blocks out); the frozen-glider bug sent ZERO
            assertTrue(flightVels >= 3, "the backward arc rides velocity legs, not 20Hz glides ("
                    + flightVels + ")");
            // per-tick absolute sets ride alongside the legs for LEGACY viewers - a 1.8 client's
            // latched arrow obeys them where velocity is invisible to it
            assertTrue(legacyPositions >= 3, "the backward arc carries per-tick sets ("
                    + legacyPositions + ")");
        } finally {
            pb.halt();
            viewer.player.remove();
            legacy.player.remove();
        }
    }
}
