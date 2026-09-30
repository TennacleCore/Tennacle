package tennacle.games.bridge;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Your own goal is a hole you fall through - on minemen it takes you where you stand. */
class OwnGoalTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        tennacle.games.TestWiring.ensure(instance);
    }

    @Test
    void minemenOwnGoalTakesYou() {
        walkIntoIt("minemen", true);
    }

    @Test
    void elsewhereItIsJustAHole() {
        walkIntoIt("scrims", false);
    }

    private void walkIntoIt(String platform, boolean takesYou) {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(platform), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "Own" + platform + "Red");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "Own" + platform + "Blue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            BridgeRoundTest.tickUntil(game, "GO", () -> Match.live(game));

            Player one = red.player;
            Slot seat = game.roster().slotOf(one);
            assertNotNull(seat);
            String side = Bridge.teamOf(game, one);
            double home = game.arena().marker(Bridge.RESPAWN, side).pos().x();
            int fell = game.state().get(Scope.slot(seat.index()), Bridge.UNCREDITED);
            // the goal region that carries their OWN side
            Pos own = "1".equals(side) ? new Pos(-7.5, 65, 0.5) : new Pos(7.5, 65, 0.5);

            EventDispatcher.call(new PlayerMoveEvent(one, own, true));
            if (takesYou) {
                BridgeRoundTest.tickUntil(game, "their own goal took them",
                        () -> game.state().get(Scope.slot(seat.index()), Bridge.UNCREDITED) == fell + 1);
                assertEquals(home, one.getPosition().x(), 0.01, "and put them back on their spawn");
            } else {
                long settle = game.state().tick() + 5;
                BridgeRoundTest.tickUntil(game, "a few ticks pass", () -> game.state().tick() >= settle);
                assertEquals(fell, game.state().get(Scope.slot(seat.index()), Bridge.UNCREDITED),
                        "the hole is the void's business, not the goal's");
            }
            assertEquals(0, game.state().get(Scope.team(side), Bridge.GOALS), "an own goal never scores");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }
}
