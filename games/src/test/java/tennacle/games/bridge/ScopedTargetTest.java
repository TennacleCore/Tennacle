package tennacle.games.bridge;

import tennacle.games.Matches;
import tennacle.games.Ruleset;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.outcome.Score;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.Scope;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A handicap is scoped, so the board reads each side's own target - the decider already did. */
class ScopedTargetTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void oneSideRunsAShorterMeter() {
        Game game = Matches.launch(Matches.mode("bridge"), Matches.platformFor("scrims"), BridgeRoundTest.fixture());
        try {
            Team red = Boards.teamAt(game, 0);
            Team blue = Boards.teamAt(game, 1);
            assertEquals(5, game.rules().resolve(Scope.team(red.id()), Score.TARGET), "bridge launches at 5");

            Matches.modify(game, Ruleset.named("live").rule(Scope.team(red.id()), "outcome.score.target", "3"));

            assertEquals(3, game.rules().resolve(Scope.team(red.id()), Score.TARGET), "red's handicap took");
            assertEquals(5, game.rules().resolve(Scope.team(blue.id()), Score.TARGET), "blue still reads the game");
            assertEquals(3, cells(Boards.meterLine(game, red)), "red's meter is three long");
            assertEquals(5, cells(Boards.meterLine(game, blue)), "blue's is untouched");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
        }
    }

    private static long cells(Component line) {
        return PlainTextComponentSerializer.plainText().serialize(line).chars().filter(c -> c == '⬛').count();
    }
}
