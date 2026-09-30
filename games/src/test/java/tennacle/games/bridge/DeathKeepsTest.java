package tennacle.games.bridge;

import tennacle.boot.Mechanics;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.polyp.MechanicsKeys;
import io.github.term4.polyp.mechanics.containers.Spill;
import io.github.term4.polyp.mechanics.damage.DeathConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** A match's inventories are the game's: every preset here drops at death, and a match keeps. */
class DeathKeepsTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void aMatchDeathKeeps() {
        Mode bridge = Matches.mode("bridge");
        assertNotNull(bridge);
        Game game = Matches.launch(bridge, Matches.platformFor(null), bridge.arena().get());
        try {
            DeathConfig death = Mechanics.polyp.profiles().resolveWorld(game.shard(), MechanicsKeys.DEATH);
            assertNotNull(death);
            assertEquals(Spill.KEEP, death.spill(new DeathConfig.DeathContext(null)));
        } finally {
            game.end(Outcome.aborted());
        }
    }
}
