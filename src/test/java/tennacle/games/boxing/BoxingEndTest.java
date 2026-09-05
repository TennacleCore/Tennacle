package tennacle.games.boxing;

import tennacle.games.Matches;
import tennacle.games.Ruleset;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.outcome.Score;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A score ending eliminates nobody, so the losing side has to fall on the decision or it stands there hittable. */
class BoxingEndTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    // the game clock runs off the scheduler, so anything on an alarm needs ticks pumped at it
    private static void awaitTicking(String what, Supplier<Boolean> done) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 4000;
        while (!done.get() && System.currentTimeMillis() < deadline) {
            MinecraftServer.getSchedulerManager().processTick();
            Thread.sleep(10);
        }
        assertTrue(done.get(), what);
    }

    private static Game awaitGame(FakePlayer p) throws InterruptedException {
        awaitTicking("the duel seated " + p.player.getUsername(), () -> Game.of(p.player) != null);
        return Game.of(p.player);
    }

    @Test
    void reachingTheHitTargetDropsTheLoser() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BoxWin");
        FakePlayer b = FakePlayer.connect(instance, new Pos(2.5, 65, 2.5), "BoxLose");
        Game game = null;
        try {
            MinecraftServer.getCommandManager().execute(a.player, "duel BoxLose boxing");
            MinecraftServer.getCommandManager().execute(b.player, "duel BoxWin");
            game = awaitGame(a);
            assertNotNull(game, "the duel launched");
            // a long linger keeps the game open past the ghosting, so the teardown cannot race the assertions
            Matches.modify(game, Ruleset.named("linger").rule("flow.end.linger", "200"));

            Slot seat = game.roster().slotOf(a.player);
            assertNotNull(seat);
            game.state().set(Scope.slot(seat.index()), Boxing.HITS, game.rules().resolve(seat, Score.TARGET));
            Game decided = game;
            awaitTicking("the score decided it", () -> decided.decided() != null);
            awaitTicking("the loser goes down", () -> b.player.isFlying());
            awaitTicking("and ghosts out of the fight", () -> decided.isGhost(b.player));
            assertFalse(a.player.isFlying(), "the winner stays on their feet");
        } finally {
            if (game != null && game.status() == Game.Status.OPEN) game.end(Outcome.draw());
            a.player.remove();
            b.player.remove();
        }
    }
}
