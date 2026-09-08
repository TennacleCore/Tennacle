package tennacle.lobby;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.TestWiring;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A death nobody answered rides into the lobby; the arrival is where it ends. */
class LobbyBodyTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void aDeadArrivalStandsUp() {
        FakePlayer p = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "LobbyCorpse");
        try {
            p.player.kill();
            assertTrue(p.player.isDead(), "dead, and nothing here respawns them");
            for (int i = 0; i < 30 && p.player.isAutoViewable(); i++) { // the hide rides the entity's own scheduler
                p.player.tick(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
            }

            Playground.outfit(p.player);
            assertFalse(p.player.isDead(), "the lobby stands them back up");
            for (int i = 0; i < 3 && !p.player.isAutoViewable(); i++) { // the restore rides it too
                p.player.tick(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
            }
            assertTrue(p.player.isAutoViewable(), "and every other client draws them again");
        } finally {
            p.player.remove();
        }
    }
}
