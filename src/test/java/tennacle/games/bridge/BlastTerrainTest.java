package tennacle.games.bridge;

import tennacle.Mechanics;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.polyp.MechanicsKeys;
import io.github.term4.polyp.mechanics.explosion.ExplosionConfig;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A match's terrain is the arena's. Every platform preset here (hypixel, mmc18) carries blast rays fitted for a
 * block-breaking game, so a match must clear them or a stray fireball rewrites the map.
 */
class BlastTerrainTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void aBlastLeavesTheMapAlone() {
        Mode bridge = Matches.mode("bridge");
        assertNotNull(bridge);
        Game game = Matches.launch(bridge, Matches.platformFor(null), bridge.arena().get());
        try {
            ExplosionConfig cfg = Mechanics.polyp.profiles().resolveWorld(game.shard(), MechanicsKeys.EXPLOSION);
            assertNotNull(cfg, "the game's world profile carries an explosion config");

            Pos at = new Pos(0.5, 70, 0.5);
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    game.shard().setBlock(new Pos(at.blockX() + dx, 69, at.blockZ() + dz), Block.OAK_PLANKS);
            // the harness boots mechanics without the systems Mechanics.install() adds
            new io.github.term4.polyp.mechanics.explosion.ExplosionSystem(Mechanics.polyp, null)
                    .explode(game.shard(), at, 2.0f, null, null);
            assertEquals(Block.OAK_PLANKS, game.shard().getBlock(new Pos(at.blockX(), 69, at.blockZ())),
                    "the ground under the blast survives it");
        } finally {
            game.end(Outcome.aborted());
        }
    }
}
