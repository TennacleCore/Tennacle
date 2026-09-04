package tennacle.games;

import tennacle.Mechanics;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.TimeLimit;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.MechanicsKeys;
import io.github.term4.polyp.mechanics.projectile.types.Arrow;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The growth list on the same shapes: a queue pairs sides, a spectator watches, a live modifier re-folds. */
class StepThreeTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    private static Game awaitGame(FakePlayer p) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 4000;
        while (Game.of(p.player) == null && System.currentTimeMillis() < deadline) {
            MinecraftServer.getSchedulerManager().processTick(); // the join bind lands on the next tick
            Thread.sleep(10);
        }
        return Game.of(p.player);
    }

    @Test
    void theQueuePairsTwoSidesAndLiveModifiersReFold() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "QueueA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(2.5, 65, 2.5), "QueueB");
        FakePlayer w = FakePlayer.connect(instance, new Pos(4.5, 65, 4.5), "QueueW");
        Game game = null;
        try {
            MinecraftServer.getCommandManager().execute(a.player, "queue sumo");
            assertTrue(Queues.waiting(a.player), "the first side waits");
            MinecraftServer.getCommandManager().execute(b.player, "queue sumo");
            game = awaitGame(a);
            assertNotNull(game, "the second side started the match");
            assertSame(game, awaitGame(b));
            assertTrue(!Queues.waiting(a.player) && !Queues.waiting(b.player));
            assertTrue(!Match.teamOf(game, a.player).equals(Match.teamOf(game, b.player)), "one side each");

            MinecraftServer.getCommandManager().execute(w.player, "spectate QueueA");
            Game watched = game;
            long deadline = System.currentTimeMillis() + 4000; // the cross-world view lands a tick after the transfer
            while (!watched.spectators().contains(w.player) && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            assertTrue(game.spectators().contains(w.player), "a watcher without a seat");
            assertNull(game.roster().slotOf(w.player));

            // a live modifier: a rule by id and a mechanics path, checked, then folded over the running game
            Matches.modify(game, Ruleset.named("live")
                    .rule("time:limit", "1200")
                    .path("projectiles/minecraft:arrow/critDamage", true));
            assertEquals(1200L, game.rules().resolve(Scope.GAME, TimeLimit.LIMIT));
            var arrow = Mechanics.polyp.profiles().resolve(a.player, MechanicsKeys.PROJECTILES).typeConfig(Arrow.KEY);
            assertEquals(Boolean.TRUE, arrow.critDamage.constantOrNull(), "the world profile re-folded");
            Game running = game;
            IllegalStateException bad = assertThrows(IllegalStateException.class,
                    () -> Matches.modify(running, Ruleset.named("typo").rule("cage:seconds", "5")));
            assertTrue(bad.getMessage().contains("not a rule of sumo"), bad.getMessage());
            assertEquals(1200L, game.rules().resolve(Scope.GAME, TimeLimit.LIMIT), "a refused entry changed nothing");
        } finally {
            if (game != null && game.status() == Game.Status.OPEN) game.end(Outcome.draw());
            a.player.remove();
            b.player.remove();
            w.player.remove();
        }
    }
}
