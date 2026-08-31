package io.github.term4.archipelago.replay;

import io.github.term4.polyp.Polyp;
import io.github.term4.polyp.mechanics.explosion.ExplosionSystem;
import io.github.term4.polyp.mechanics.projectile.ProjectileSnapshot;
import io.github.term4.polyp.mechanics.projectile.ProjectileSystem;
import io.github.term4.polyp.mechanics.projectile.entities.ProjectileEntity;
import io.github.term4.polyp.mechanics.projectile.types.Snowball;
import io.github.term4.polyp.entity.PrimedTnt;
import io.github.term4.polyp.presets.mmc18.Explosion;
import io.github.term4.polyp.entity.DroppedItemEntity;
import io.github.term4.polyp.world.MechanicsWorld;
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
import net.minestom.server.network.packet.server.play.EntityPositionPacket;
import net.minestom.server.network.packet.server.play.EntityPositionSyncPacket;
import net.minestom.server.network.packet.server.play.EntityTeleportPacket;
import net.minestom.server.network.packet.server.play.EntityVelocityPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.bridge.ReplayBridge;
import tennacle.bridge.ShardBridge;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression gates for the resync paths that produced in-game rubber-banding: the missed-first-leg
 *  Zeno crawl at low rates, and the rest-pin ground-snap on a drifted free-run twin. */
class ResyncPathWireTest extends HeadlessServerTest {

    private static ExplosionSystem explosions;
    private static ProjectileSystem projectiles;

    @BeforeAll
    static void mechanics() {
        Polyp polyp = Polyp.getInstance();
        polyp.init();
        ShardBridge.install();
        ReplayBridge.install();
        // wire tests emulate specific generations: viewer names carry the split
        Replay.legacyViewers(p -> p.getUsername().contains("Legacy"));
        explosions = new ExplosionSystem(polyp, Explosion.config());
        projectiles = new ProjectileSystem(polyp, io.github.term4.polyp.presets.hypixel.Projectiles.config());
        EntityDescriptors.describeEntity(e -> {
            var stamp = e.getTag(MechanicsWorld.ENTITY_SAVE);
            return stamp != null ? stamp.get() : null;
        });
        EntityDescriptors.reviveEntity("polyp:item", DroppedItemEntity::fromSave);
        EntityDescriptors.reviveEntity("polyp:projectile", projectiles::fromSave);
        EntityDescriptors.reviveEntity("polyp:tnt", d -> PrimedTnt.fromSave(explosions, d));
    }

    private static void tick() {
        MinecraftServer.getSchedulerManager().processTick();
        MinecraftServer.getSchedulerManager().processTickEnd();
    }

    /** The spawn defer eats the launch crossing, so the first window flies on pin legs. Un-stored, they
     *  rebuilt each tick and the step decayed by (1-rate) - a Zeno crawl that left every low-rate
     *  throwable a leg behind for its whole flight (the "collision rubber-band"). The window's wire must
     *  hold the scaled launch leg steady. */
    @Test
    void tenthRateFirstWindowHoldsTheLaunchLeg() {
        Shard game = new Shard(instance);
        FakePlayer thrower = FakePlayer.connect(instance, new Pos(170.5, 70, 170.5), "ZenoThrower");
        game.join(thrower.player);
        Recording rec = Replay.record(game);
        byte[] file;
        ProjectileEntity ball = null;
        double launchSpeed = 0;
        try {
            tick();
            ball = projectiles.launch(ProjectileSnapshot.of(thrower.player, Snowball.INSTANCE)
                    .withConfig(io.github.term4.polyp.presets.hypixel.Projectiles.config()));
            assertNotNull(ball);
            launchSpeed = ball.velocityBt().length();
            MinecraftServer.getSchedulerManager().processTickEnd();
            for (int i = 0; i < 12; i++) {
                if (!ball.isRemoved()) ball.tick(i * 50L);
                tick();
            }
        } finally {
            file = rec.stop();
            if (ball != null && !ball.isRemoved()) ball.remove();
            game.leave(thrower.player);
            thrower.player.remove();
        }

        Shard theater = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(170.5, 70, 170.5), "ZenoViewer");
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
            for (int i = 0; i < 20 && twin.getInstance() == null; i++) tick();

            int id = twin.getEntityId();
            List<Double> window = new ArrayList<>();
            viewer.sent.clear();
            for (int t = 0; t < 12 && window.size() < 9 && !twin.isRemoved(); t++) {
                twin.tick(t * 50L);
                tick();
                for (SendablePacket sp : List.copyOf(viewer.sent)) {
                    var pk = SendablePacket.extractServerPacket(ConnectionState.PLAY, sp);
                    if (pk instanceof EntityVelocityPacket p && p.entityId() == id) window.add(p.velocity().length());
                }
                viewer.sent.clear();
            }
            System.out.println("[zeno-wire] launch=" + String.format("%.3f", launchSpeed)
                    + " window=" + window.stream().map(v -> String.format("%.3f", v)).toList());
            assertTrue(window.size() >= 8, "per-tick re-aims fill the first window (saw " + window.size() + ")");
            double first = window.get(0);
            assertTrue(Math.abs(first - launchSpeed * 0.1) < launchSpeed * 0.03,
                    "the first leg is the rate-scaled launch (saw " + first + " for launch " + launchSpeed + ")");
            for (double v : window) {
                assertTrue(v >= first * 0.85, "the window's legs hold steady - no Zeno decay (saw " + v
                        + " after " + first + ")");
            }
        } finally {
            pb.halt();
            viewer.player.remove();
        }
    }

    /** A free-run twin that drifts off a SILENT track (the mmc18 bounce threshold makes resim-vs-track
     *  divergence a fact of life) must be pulled back by legs, not the old zero+teleport - that snap was
     *  the in-game "TNT teleports back to the ground after bouncing" at rate 1. */
    @Test
    void settledDriftPullsBackWithoutTeleport() {
        Shard game = new Shard(instance);
        Recording rec = Replay.record(game);
        byte[] file;
        PrimedTnt tnt = null;
        try {
            tick();
            tnt = PrimedTnt.spawn(explosions, instance, new BlockVec(190, 76, 190),
                    new PrimedTnt.Config(200, 4.0f, true, PrimedTnt.Wire.MINEMEN, true, null, false));
            game.transfer(tnt);
            for (int i = 0; i < 90; i++) {
                tnt.tick(0);
                tick();
            }
            assertTrue(tnt.isOnGround(), "the recorded TNT settled");
        } finally {
            file = rec.stop();
            if (tnt != null) tnt.remove();
        }

        Shard theater = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(193.5, 66, 190.5), "DriftViewer");
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
            for (int i = 0; i < 50 && !twin.isRemoved()
                    && !(twin.getPosition().y() < 64.3 && Math.abs(twin.getVelocity().y()) < 0.5); i++) {
                twin.tick(0);
                tick();
            }
            assertTrue(twin.getPosition().y() < 64.3, "the twin settled (premise)");
            Pos rest = twin.getPosition();

            // a shade of resim divergence: the twin ends up half a block off a track that has gone quiet
            // (visible, like a live divergence - the hand wire broadcasts a real drift as it builds)
            twin.teleport(rest.add(0, 0.5, 0)).join();
            tick();
            int id = twin.getEntityId();
            Pos clientPos = twin.getPosition();
            net.minestom.server.coordinate.Vec clientVel = net.minestom.server.coordinate.Vec.ZERO;
            double maxJump = 0;
            viewer.sent.clear();
            for (int i = 0; i < 25 && !twin.isRemoved(); i++) {
                twin.tick(0);
                tick();
                for (SendablePacket sp : List.copyOf(viewer.sent)) {
                    var pk = SendablePacket.extractServerPacket(ConnectionState.PLAY, sp);
                    if (pk instanceof EntityVelocityPacket p && p.entityId() == id) {
                        clientVel = p.velocity();
                    } else if (pk instanceof EntityTeleportPacket p && p.entityId() == id) {
                        maxJump = Math.max(maxJump, clientPos.distance(p.position().asPos()));
                        clientPos = p.position().asPos();
                    } else if (pk instanceof EntityPositionSyncPacket p && p.entityId() == id) {
                        maxJump = Math.max(maxJump, clientPos.distance(p.position().asPos()));
                        clientPos = p.position().asPos();
                    }
                }
                viewer.sent.clear();
                clientPos = clientPos.add(clientVel); // parked comes with NO_GRAVITY: a linear client sim
            }
            double gap = twin.getPosition().distance(rest);
            System.out.println("[drift-pull] maxJump=" + String.format("%.3f", maxJump)
                    + " gap=" + String.format("%.3f", gap));
            assertTrue(maxJump < 0.3, "the pull-back rides velocity legs, never an absolute snap (saw "
                    + maxJump + ")");
            assertTrue(gap < 0.25, "the drifted twin re-converged onto the pin (still " + gap + " off)");
        } finally {
            pb.halt();
            viewer.player.remove();
        }
    }
    /** Pausing at FULL rate must freeze twins too - free-run twins kept simulating through a pause (the
     *  in-game "TNT and projectiles don't pause at rate 1"); parked twins hold on a zeroed leg. */
    @Test
    void pauseFreezesFullRateTwins() {
        Shard game = new Shard(instance);
        Recording rec = Replay.record(game);
        byte[] file;
        PrimedTnt tnt = null;
        try {
            tick();
            tnt = PrimedTnt.spawn(explosions, instance, new BlockVec(210, 100, 210),
                    new PrimedTnt.Config(200, 4.0f, true, PrimedTnt.Wire.MINEMEN, true, null, false));
            game.transfer(tnt);
            for (int i = 0; i < 40; i++) {
                tnt.tick(0);
                tick();
            }
        } finally {
            file = rec.stop();
            if (tnt != null) tnt.remove();
        }

        Shard theater = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(213.5, 66, 210.5), "PauseLegacy");
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
            double y0 = twin.getPosition().y();
            for (int i = 0; i < 30 && twin.getPosition().y() >= y0 - 1; i++) { // past the spawn-kick hop
                twin.tick(0);
                tick();
            }
            assertTrue(twin.getPosition().y() < y0 - 1, "mid-fall (premise)");

            pb.pause(true);
            twin.tick(0);
            tick(); // the pause's zero-velocity flush
            Pos held = twin.getPosition();
            int id = twin.getEntityId();
            viewer.sent.clear();
            // pause is not wire-silence: a 1.8-generation TNT keeps integrating its own gravity through a
            // quiet wire and falls out of the pause - the wire must HOLD it (same-spot absolutes + zero
            // velocity each tick), never move it
            int holds = 0, movers = 0;
            for (int i = 0; i < 15; i++) {
                twin.tick(0);
                tick();
                for (SendablePacket sp : List.copyOf(viewer.sent)) {
                    var pk = SendablePacket.extractServerPacket(ConnectionState.PLAY, sp);
                    if (pk instanceof EntityVelocityPacket p && p.entityId() == id
                            && p.velocity().length() > 1.0e-3) movers++;
                    if (pk instanceof EntityPositionSyncPacket r && r.entityId() == id) {
                        if (new Pos(r.position().x(), r.position().y(), r.position().z())
                                .distance(held) < 1.0e-6) holds++;
                        else movers++;
                    }
                    if (pk instanceof EntityPositionPacket m && m.entityId() == id
                            && (m.deltaX() != 0 || m.deltaY() != 0 || m.deltaZ() != 0)) movers++;
                }
                viewer.sent.clear();
            }
            System.out.println("[pause-hold] moved=" + String.format("%.4f", twin.getPosition().distance(held))
                    + " holds=" + holds + " movers=" + movers);
            assertTrue(twin.getPosition().distance(held) < 1.0e-6, "the paused twin holds in place");
            assertTrue(holds >= 12, "the pause actively pins the client (saw " + holds + " holds)");
            assertTrue(movers == 0, "a paused twin's wire never moves it (saw " + movers + ")");
        } finally {
            pb.halt();
            viewer.player.remove();
        }
    }
    /** A recorded launch stalls 1-2 server ticks (the cross-clock pickup); slow motion magnified that hole
     *  into the in-game "spawn, pause, resume". Short holes must BRIDGE toward the next known point -
     *  no stop-dead zero, no dead air. */
    @Test
    void trackHoleBridgesWithoutStopDead() {
        Shard game = new Shard(instance);
        Entity ball = new Entity(net.minestom.server.entity.EntityType.SNOWBALL);
        Recording rec = Replay.record(game);
        byte[] file;
        try {
            game.spawn(ball, new Pos(300.5, 70, 300.5)).join();
            tick(); // sighting -> spawn op
            rec.emit(io.github.term4.archipelago.replay.core.Channels.ENTITY_MOVES,
                    new io.github.term4.archipelago.replay.core.Channels.EntityMove(0, 300.5, 70, 302.0, 0, 0));
            tick();
            tick(); // the hole: two silent recorded ticks
            tick();
            rec.emit(io.github.term4.archipelago.replay.core.Channels.ENTITY_MOVES,
                    new io.github.term4.archipelago.replay.core.Channels.EntityMove(0, 300.5, 70, 306.5, 0, 0));
            tick();
            rec.emit(io.github.term4.archipelago.replay.core.Channels.ENTITY_MOVES,
                    new io.github.term4.archipelago.replay.core.Channels.EntityMove(0, 300.5, 70, 308.0, 0, 0));
            tick();
        } finally {
            file = rec.stop();
            ball.remove();
        }

        Shard theater = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(300.5, 70, 300.5), "HoleViewer");
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
            int id = twin.getEntityId();
            int zeros = 0, silent = 0, maxSilent = 0;
            boolean sawMotion = false;
            viewer.sent.clear();
            for (int t = 0; t < 30 && !twin.isRemoved(); t++) {
                twin.tick(0);
                tick();
                boolean motion = false;
                for (SendablePacket sp : List.copyOf(viewer.sent)) {
                    var pk = SendablePacket.extractServerPacket(ConnectionState.PLAY, sp);
                    if (pk instanceof EntityVelocityPacket p && p.entityId() == id) {
                        if (p.velocity().length() <= 1.0e-3) zeros++;
                        else motion = true;
                    }
                }
                viewer.sent.clear();
                if (motion) {
                    sawMotion = true;
                    silent = 0;
                } else if (sawMotion) {
                    maxSilent = Math.max(maxSilent, ++silent);
                }
            }
            System.out.println("[hole-wire] zeros=" + zeros + " maxSilent=" + maxSilent);
            assertTrue(zeros <= 1, "no stop-dead inside a bridged hole (saw " + zeros + " zeros; only the end rest may zero)");
            assertTrue(maxSilent <= 2, "the bridge leg keeps the wire alive across the hole (silent "
                    + maxSilent + " ticks)");
        } finally {
            pb.halt();
            viewer.player.remove();
        }
    }

    /** The launch hole for the WHOLE projectile family: spawn, one leg, a 2-recorded-tick stall (the
     *  live cross-clock pickup), then steady flight. The in-game report was spawn, a bit of motion, a
     *  PAUSE, then smooth flight - the rendered modern path (velocity sim for throwables, anchors + the
     *  accel sim for fireballs) must move continuously through it, paced, for every type. */
    @Test
    void launchHoleKeepsRenderingAllProjectiles() {
        for (int cx = 20; cx <= 25; cx++)
            for (int cz = 18; cz <= 20; cz++) instance.loadChunk(cx, cz).join();
        net.minestom.server.entity.EntityType[] family = {
                net.minestom.server.entity.EntityType.SNOWBALL, net.minestom.server.entity.EntityType.EGG,
                net.minestom.server.entity.EntityType.ENDER_PEARL, net.minestom.server.entity.EntityType.SPLASH_POTION,
                net.minestom.server.entity.EntityType.FIREBALL, net.minestom.server.entity.EntityType.ARROW};
        for (int n = 0; n < family.length; n++) {
            net.minestom.server.entity.EntityType type = family[n];
            boolean accel = type == net.minestom.server.entity.EntityType.FIREBALL;
            double x = 340.5 + 8 * n;
            Shard game = new Shard(instance);
            Entity ball = new Entity(type);
            Recording rec = Replay.record(game);
            byte[] file;
            try {
                game.spawn(ball, new Pos(x, 70, 300.5)).join();
                tick(); // sighting -> spawn op
                rec.emit(io.github.term4.archipelago.replay.core.Channels.ENTITY_MOVES,
                        new io.github.term4.archipelago.replay.core.Channels.EntityMove(0, x, 70, 302.0, 0, 0));
                tick();
                tick(); // the hole: two silent recorded ticks
                tick();
                for (int i = 1; i <= 3; i++) {
                    rec.emit(io.github.term4.archipelago.replay.core.Channels.ENTITY_MOVES,
                            new io.github.term4.archipelago.replay.core.Channels.EntityMove(0, x, 70, 304.5 + 1.5 * i, 0, 0));
                    tick();
                }
            } finally {
                file = rec.stop();
                ball.remove();
            }

            Shard theater = new Shard(instance);
            FakePlayer viewer = FakePlayer.connect(instance, new Pos(x, 70, 300.5), "HoleSweep" + n);
            theater.join(viewer.player);
            Playback pb = Replay.play(theater, file);
            pb.rate(0.25);
            try {
                Entity twin = null;
                for (int i = 0; i < 100 && twin == null; i++) {
                    tick();
                    twin = pb.entity(0);
                }
                assertNotNull(twin, type + ": the twin rose");
                for (int i = 0; i < 20 && twin.getInstance() == null; i++) tick();
                int id = twin.getEntityId();
                Pos clientPos = twin.getPosition();
                net.minestom.server.coordinate.Vec clientVel = net.minestom.server.coordinate.Vec.ZERO;
                boolean sawMotion = false;
                int silent = 0, maxSilent = 0;
                double maxStep = 0;
                StringBuilder log = new StringBuilder();
                viewer.sent.clear();
                for (int t = 0; t < 40 && !twin.isRemoved(); t++) {
                    twin.tick(0);
                    tick();
                    Pos before = clientPos;
                    for (SendablePacket sp : List.copyOf(viewer.sent)) {
                        var pk = SendablePacket.extractServerPacket(ConnectionState.PLAY, sp);
                        if (pk instanceof EntityVelocityPacket p && p.entityId() == id) {
                            clientVel = p.velocity();
                        } else if (pk instanceof EntityPositionSyncPacket p && p.entityId() == id) {
                            clientPos = new Pos(p.position().x(), p.position().y(), p.position().z());
                        } else if (pk instanceof EntityTeleportPacket p && p.entityId() == id) {
                            clientPos = new Pos(p.position().x(), p.position().y(), p.position().z());
                        }
                    }
                    viewer.sent.clear();
                    if (clientVel.length() > 1.0e-9) { // the 26.1 client sim, packet-steered
                        clientVel = accel ? clientVel.add(clientVel.normalize().mul(0.1)).mul(0.95)
                                : clientVel.mul(0.99);
                        clientPos = clientPos.add(clientVel);
                    }
                    double step = clientPos.distance(before);
                    log.append(String.format("%.3f ", step));
                    if (step > 5.0e-3) {
                        sawMotion = true;
                        silent = 0;
                        maxStep = Math.max(maxStep, step);
                    } else if (sawMotion) {
                        maxSilent = Math.max(maxSilent, ++silent);
                    }
                }
                System.out.println("[hole-sweep] " + type.name() + " maxSilent=" + maxSilent
                        + " maxStep=" + String.format("%.3f", maxStep) + " steps=" + log);
                assertTrue(sawMotion, type + ": rendered motion at all");
                assertTrue(maxSilent <= 2, type + ": never pauses through the launch hole (silent "
                        + maxSilent + " ticks)");
                // the pre-fix launch lost every deferred-window leg: still, a snap, then a rush.
                // steady flight here is 0.375/tick; a bridge ramp may run slightly hot, never a snap
                assertTrue(maxStep < 1.0, type + ": the launch renders paced, no snap-then-rush (maxStep "
                        + maxStep + ")");
            } finally {
                pb.halt();
                viewer.player.remove();
            }
        }
    }
    /** A recorded rod cast lands on water and FLOATS; the replayed twin must ride that track - the
     *  in-game report was the replay bobber flying through the surface as if the water weren't there. */
    @Test
    void bobberTwinFloatsLikeTheRecording() {
        for (int x = 495; x <= 515; x++)
            for (int y = 60; y <= 64; y++)
                for (int z = 495; z <= 515; z++) instance.setBlock(x, y, z, net.minestom.server.instance.block.Block.WATER);
        Shard game = new Shard(instance);
        FakePlayer angler = FakePlayer.connect(instance, new Pos(500.5, 66, 497.5, 0, 10), "FloatAngler");
        angler.player.setItemInMainHand(net.minestom.server.item.ItemStack.of(net.minestom.server.item.Material.FISHING_ROD)); // the bobber's per-tick holding-a-rod check
        game.join(angler.player);
        Recording rec = Replay.record(game);
        byte[] file;
        ProjectileEntity bobber = null;
        double recordedMinY = 999, recordedEndY = 0;
        try {
            tick();
            bobber = projectiles.launch(ProjectileSnapshot.of(angler.player, io.github.term4.polyp.mechanics.projectile.types.FishingBobber.INSTANCE)
                    .withConfig(io.github.term4.polyp.presets.vanilla18.Vanilla18.projectiles()));
            assertNotNull(bobber);
            MinecraftServer.getSchedulerManager().processTickEnd();
            for (int i = 0; i < 60; i++) {
                if (!bobber.isRemoved()) {
                    bobber.tick(i * 50L);
                    recordedMinY = Math.min(recordedMinY, bobber.getPosition().y());
                    recordedEndY = bobber.getPosition().y();
                }
                tick();
            }
            assertTrue(recordedEndY > 64.0, "the recorded bobber floats near the surface (premise, ended y="
                    + recordedEndY + ")");
        } finally {
            file = rec.stop();
            if (bobber != null && !bobber.isRemoved()) bobber.remove();
            game.leave(angler.player);
            angler.player.remove();
        }

        for (double rate : new double[]{1.0, 0.25}) {
            Shard theater = new Shard(instance);
            FakePlayer viewer = FakePlayer.connect(instance, new Pos(500.5, 66, 497.5), "FloatViewer" + (int) (rate * 100));
            theater.join(viewer.player);
            Playback pb = Replay.play(theater, file);
            pb.rate(rate);
            try {
                Entity twin = null;
                for (int i = 0; i < 200 && twin == null; i++) {
                    tick();
                    twin = pb.entity(0);
                }
                assertNotNull(twin, "the twin rose (rate " + rate + ")");
                double twinMinY = 999, twinEndY = 0;
                for (int i = 0; i < (int) (80 / rate) && !twin.isRemoved(); i++) {
                    tick();
                    if (twin.getInstance() != null) {
                        twinMinY = Math.min(twinMinY, twin.getPosition().y());
                        twinEndY = twin.getPosition().y();
                    }
                }
                System.out.println("[bobber-float] rate=" + rate + " recorded min/end="
                        + String.format("%.2f/%.2f", recordedMinY, recordedEndY)
                        + " twin min/end=" + String.format("%.2f/%.2f", twinMinY, twinEndY));
                assertTrue(twinMinY >= recordedMinY - 0.3,
                        "the twin never dives below the recorded path (twin " + twinMinY + " vs " + recordedMinY + ")");
                assertTrue(Math.abs(twinEndY - recordedEndY) < 0.5,
                        "the twin ends floating where the recording did (" + twinEndY + " vs " + recordedEndY + ")");
            } finally {
                pb.halt();
                viewer.player.remove();
            }
        }
    }
    /** The user's screenshot: a SHARD-built floating water column, and the live bobber sailed straight
     *  through it - an un-transferred projectile resolves the BASE map, so shard water is invisible to
     *  its physics. The launch routing must put the bobber in the shooter's shard. */
    @Test
    void bobberFloatsOnShardWater() {
        Shard game = new Shard(instance);
        FakePlayer angler = FakePlayer.connect(instance, new Pos(520.5, 66, 490.5, 0, 0), "ShardAngler");
        angler.player.setItemInMainHand(net.minestom.server.item.ItemStack.of(net.minestom.server.item.Material.FISHING_ROD));
        game.join(angler.player);
        for (int x = 516; x <= 524; x++)
            for (int y = 64; y <= 70; y++)
                for (int z = 499; z <= 510; z++)
                    game.setBlock(new Pos(x, y, z), net.minestom.server.instance.block.Block.WATER);
        ProjectileEntity bobber = null;
        try {
            tick();
            bobber = projectiles.launch(ProjectileSnapshot.of(angler.player, io.github.term4.polyp.mechanics.projectile.types.FishingBobber.INSTANCE)
                    .withConfig(io.github.term4.polyp.presets.vanilla18.Vanilla18.projectiles()));
            assertNotNull(bobber);
            MinecraftServer.getSchedulerManager().processTickEnd(); // the launch routing transfers end-of-tick
            assertTrue(bobber.getTag(io.github.term4.polyp.world.MechanicsWorld.ENTITY_TAG) == game,
                    "the launch routed the bobber into the shooter's shard");
            double maxZ = 0;
            for (int i = 0; i < 80 && !bobber.isRemoved(); i++) {
                bobber.tick(i * 50L);
                maxZ = Math.max(maxZ, bobber.getPosition().z());
                tick();
            }
            System.out.println("[shard-water] maxZ=" + String.format("%.2f", maxZ)
                    + " end=" + bobber.getPosition());
            assertTrue(maxZ < 510.5, "the bobber stopped IN the shard water instead of sailing through (maxZ "
                    + maxZ + ")");
            assertTrue(bobber.getPosition().y() > 69.5,
                    "it floated to the shard water's surface (y " + bobber.getPosition().y() + ")");
        } finally {
            if (bobber != null && !bobber.isRemoved()) bobber.remove();
            game.leave(angler.player);
            angler.player.remove();
        }
    }
    /** The PROD shape of the shard-water cast: a DOMAIN-bound game shard + the mmc18 preset + the launch
     *  transferring cross-clock. mmc18 hooks run plain 1.8 water physics (momentum through thin water,
     *  float in pools) - this deep column must float it, proving the cross-clock shard-water routing. */
    @Test
    void bobberFloatsOnShardWaterInProdShape() {
        io.github.term4.archipelago.ticking.ExecutionDomain clock =
                io.github.term4.archipelago.ticking.ExecutionDomain.create();
        Shard game = new Shard(instance, clock);
        FakePlayer angler = FakePlayer.connect(instance, new Pos(540.5, 66, 490.5, 0, 0), "ProdAngler");
        angler.player.setItemInMainHand(net.minestom.server.item.ItemStack.of(net.minestom.server.item.Material.FISHING_ROD));
        game.join(angler.player);
        for (int x = 536; x <= 544; x++)
            for (int y = 64; y <= 70; y++)
                for (int z = 499; z <= 510; z++)
                    game.setBlock(new Pos(x, y, z), net.minestom.server.instance.block.Block.WATER);
        ProjectileEntity bobber = null;
        try {
            var config = io.github.term4.polyp.presets.mmc18.Projectiles.config();
            bobber = new io.github.term4.polyp.mechanics.projectile.ProjectileSystem(
                    io.github.term4.polyp.Polyp.getInstance(), config)
                    .launch(ProjectileSnapshot.of(angler.player,
                            io.github.term4.polyp.mechanics.projectile.types.FishingBobber.INSTANCE)
                            .withConfig(config));
            assertNotNull(bobber);
            double maxZ = 0, endY = 0;
            boolean transferred = false;
            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline && !bobber.isRemoved()) {
                tick(); // main scheduler: the end-of-tick transfer; the DOMAIN ticks the bobber itself
                maxZ = Math.max(maxZ, bobber.getPosition().z());
                endY = bobber.getPosition().y();
                transferred |= bobber.getTag(io.github.term4.polyp.world.MechanicsWorld.ENTITY_TAG) == game;
                if (endY > 69.5 && transferred && Math.abs(bobber.getVelocity().y()) < 1.0) break; // floating
            }
            System.out.println("[prod-water] transferred=" + transferred
                    + " maxZ=" + String.format("%.2f", maxZ) + " endY=" + String.format("%.2f", endY));
            assertTrue(transferred, "the cross-clock launch routing landed");
            assertTrue(maxZ < 510.5, "stopped in the shard water (maxZ " + maxZ + ")");
            assertTrue(endY > 69.5, "the mmc18 hook floats in a deep pool like vanilla 1.8 (y " + endY + ")");
        } finally {
            if (bobber != null && !bobber.isRemoved()) bobber.remove();
            game.leave(angler.player);
            angler.player.remove();
            clock.retire();
        }
    }
    /** DIAGNOSTIC: the full 1.8-client-rendered arrow arc under the MIXED carrier, forward at rate 1,
     *  against the recorded track. Prints deviations; gates loosely. */
    @Test
    void mixedCarrierArrowArcTracksTheRecording() {
        for (int x = 555; x <= 562; x++)
            for (int y = 68; y <= 74; y++) instance.setBlock(x, y, 504, net.minestom.server.instance.block.Block.STONE);
        Shard game = new Shard(instance);
        FakePlayer thrower = FakePlayer.connect(instance, new Pos(558.5, 72, 495.5, 0, 5), "ArcThrower");
        game.join(thrower.player);
        Recording rec = Replay.record(game);
        byte[] file;
        ProjectileEntity arrow = null;
        java.util.List<Pos> recorded = new java.util.ArrayList<>();
        try {
            tick();
            arrow = projectiles.launch(ProjectileSnapshot.of(thrower.player,
                    io.github.term4.polyp.mechanics.projectile.types.Arrow.INSTANCE)
                    .withConfig(io.github.term4.polyp.presets.vanilla18.Vanilla18.projectiles()));
            assertNotNull(arrow);
            MinecraftServer.getSchedulerManager().processTickEnd();
            long time = System.currentTimeMillis();
            for (int i = 0; i < 25; i++) {
                arrow.tick(time + i * 50L);
                recorded.add(arrow.getPosition());
                tick();
            }
        } finally {
            file = rec.stop();
            if (arrow != null) arrow.remove();
            game.leave(thrower.player);
            thrower.player.remove();
        }

        Shard theater = new Shard(instance);
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(558.5, 72, 495.5), "ArcLegacyViewer");
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
            // 1.8 client: positions are server-anchored SETS, velocity sets motion, own sim runs every tick
            Pos client = twin.getPosition();
            net.minestom.server.coordinate.Vec vel = net.minestom.server.coordinate.Vec.ZERO;
            double anchorX = client.x(), anchorY = client.y(), anchorZ = client.z();
            double maxDev = 0;
            boolean latched = false;
            long time = System.currentTimeMillis();
            StringBuilder devs = new StringBuilder();
            for (int t = 0; t < 30 && !twin.isRemoved(); t++) {
                twin.tick(time + t * 50L);
                tick();
                for (SendablePacket sp : List.copyOf(viewer.sent)) {
                    var pk = SendablePacket.extractServerPacket(ConnectionState.PLAY, sp);
                    if (pk instanceof EntityVelocityPacket p && p.entityId() == id) {
                        vel = p.velocity();
                    } else if (pk instanceof net.minestom.server.network.packet.server.play.EntityPositionPacket p
                            && p.entityId() == id) {
                        anchorX += p.deltaX() / 4096.0; anchorY += p.deltaY() / 4096.0; anchorZ += p.deltaZ() / 4096.0;
                        client = new Pos(anchorX, anchorY, anchorZ);
                    } else if (pk instanceof net.minestom.server.network.packet.server.play.EntityPositionAndRotationPacket p
                            && p.entityId() == id) {
                        anchorX += p.deltaX() / 4096.0; anchorY += p.deltaY() / 4096.0; anchorZ += p.deltaZ() / 4096.0;
                        client = new Pos(anchorX, anchorY, anchorZ);
                    } else if (pk instanceof EntityTeleportPacket p && p.entityId() == id) {
                        anchorX = p.position().x(); anchorY = p.position().y(); anchorZ = p.position().z();
                        client = p.position().asPos();
                    } else if (pk instanceof EntityPositionSyncPacket p && p.entityId() == id) {
                        anchorX = p.position().x(); anchorY = p.position().y(); anchorZ = p.position().z();
                        client = p.position().asPos();
                    }
                }
                viewer.sent.clear();
                // its own sim: 1.8 arrow gravity + drag; the collision ray latches inGround at the wall
                // (z >= 503.5) and the sim freezes - like the real client
                if (!latched && client.z() >= 503.5) latched = true;
                if (!latched) {
                    client = client.add(vel);
                    vel = new net.minestom.server.coordinate.Vec(vel.x() * 0.99, (vel.y() - 0.05) * 0.99, vel.z() * 0.99);
                }
                // deviation vs the nearest recorded point
                double best = 999;
                for (Pos r : recorded) best = Math.min(best, r.distance(client));
                maxDev = Math.max(maxDev, best);
                devs.append(String.format("%.2f ", best));

            }
            StringBuilder recZ = new StringBuilder();
            for (Pos r : recorded) recZ.append(String.format("%.2f ", r.z()));
            System.out.println("[arrow-arc] recorded z: " + recZ);
            System.out.println("[arrow-arc] per-tick dev: " + devs);
            System.out.println("[arrow-arc] maxDev=" + String.format("%.2f", maxDev));
            assertTrue(maxDev < 3.0, "the rendered arc stays near the recorded track incl. one coast tick (maxDev "
                    + maxDev + ")");
        } finally {
            pb.halt();
            viewer.player.remove();
        }
    }
}
