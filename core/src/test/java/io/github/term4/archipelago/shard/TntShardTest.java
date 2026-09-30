package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.archipelago.testsupport.MechanicsBoot;
import io.github.term4.polyp.world.MechanicsWorld;
import io.github.term4.polyp.Polyp;
import io.github.term4.polyp.entity.PrimedTnt;
import io.github.term4.polyp.mechanics.explosion.ExplosionSystem;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.glue.ShardBridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Igniting a BASE-map TNT block from inside a shard is an overlay edit: that shard sees air + the entity, the base and every other shard keep the block. */
class TntShardTest extends HeadlessServerTest {

    private static Polyp polyp;

    @BeforeAll
    static void mechanics() {
        polyp = MechanicsBoot.ensure();
    }

    @Test
    void baseTntIgnitedInsideAShardStaysEverywhereElse() {
        instance.loadChunk(2400 >> 4, 2400 >> 4).join();
        Shard a = new Shard(instance);
        Shard b = new Shard(instance);
        Vec pos = new Vec(2400, 64, 2400);
        instance.setBlock(pos, Block.TNT);
        try {
            ExplosionSystem explosions = polyp.module(ExplosionSystem.class);
            if (explosions == null) explosions = ExplosionSystem.install(polyp,
                    io.github.term4.polyp.presets.vanilla18.Explosion.config());

            PrimedTnt tnt = PrimedTnt.ignite(explosions, a, pos, PrimedTnt.VANILLA, null);

            assertNotNull(tnt, "the base block is visible through the shard view, so it ignites");
            assertTrue(a.getBlock(pos).air(), "the igniting shard sees the overlay clear");
            assertEquals(Block.TNT, instance.getBlock(pos), "the base map keeps its block");
            assertEquals(Block.TNT, b.getBlock(pos), "so does every other shard");
            assertEquals(a, tnt.getTag(MechanicsWorld.ENTITY_TAG), "the entity belongs to the igniting shard");
        } finally {
            a.reset();
            b.reset();
            instance.setBlock(pos, Block.AIR);
        }
    }
}
