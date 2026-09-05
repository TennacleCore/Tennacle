package tennacle.games;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.TimeLimit;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A rule set for one side leaves the rest of the game on the game's own value. */
class ScopedRuleTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void aTeamKeepsItsOwn() {
        Mode bridge = Matches.mode("bridge");
        assertNotNull(bridge);
        Game game = Matches.launch(bridge, Matches.platformFor(null), bridge.arena().get());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "ScopedRed");
        try {
            red.player.setInstance(game.instance(), new Pos(0.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            Slot seat = game.roster().slotOf(red.player);
            assertNotNull(seat);

            Matches.modify(game, Ruleset.named("live")
                    .rule(Scope.team(seat.team()), TimeLimit.LIMIT, 1200L));
            assertEquals(1200L, game.rules().resolve(seat, TimeLimit.LIMIT), "the side takes it");
            assertEquals(15L * 60 * 20, game.rules().resolve(Scope.GAME, TimeLimit.LIMIT), "the game does not");
        } finally {
            game.end(Outcome.aborted());
            red.player.remove();
        }
    }
}
