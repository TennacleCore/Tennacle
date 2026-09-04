package tennacle;

import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The idle-chunk reaper keeps what the world cannot rebuild. The lobby's pads and lanes are placed by hand over
 * a generator that fills plain obsidian, so reaping their chunks would quietly rebuild the lobby without them.
 */
class ChunkReapTest extends HeadlessServerTest {

    @Test
    void theBuiltAreaSurvivesAndTheStraysGo() {
        InstanceContainer world = MinecraftServer.getInstanceManager().createInstanceContainer();
        world.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.OBSIDIAN));
        world.setChunkSupplier(LightingChunk::new);
        try {
            world.loadChunk(0, 0).join();
            world.loadChunk(400, 400).join(); // where a flying viewer went
            world.setBlock(4, 41, 4, Block.CACTUS); // hand placed: the generator knows nothing about it

            TestServer.reap(world, -1, -1, 1, 1);

            assertTrue(world.isChunkLoaded(0, 0), "the built area is never reaped");
            assertEquals(Block.CACTUS, world.getBlock(4, 41, 4), "and the blocks placed into it survive");
            assertFalse(world.isChunkLoaded(400, 400), "a chunk nobody is near, outside the built area, goes");
        } finally {
            MinecraftServer.getInstanceManager().unregisterInstance(world);
        }
    }
}
