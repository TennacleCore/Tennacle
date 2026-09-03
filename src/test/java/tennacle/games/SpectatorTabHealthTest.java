package tennacle.games;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.ScoreboardObjectivePacket;
import net.minestom.server.network.packet.server.play.UpdateScorePacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A spectator gets the tab health column: every player's row, and their own row carrying their own health. */
class SpectatorTabHealthTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void aSpectatorSeesThePlayersHealthAndTheirOwnOnTheirRow() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "TabHpA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(2.5, 65, 2.5), "TabHpB");
        FakePlayer w = FakePlayer.connect(instance, new Pos(4.5, 65, 4.5), "TabHpWatch");
        Game game = null;
        try {
            Mode sumo = Matches.mode("sumo");
            assertNotNull(sumo);
            game = Matches.start(sumo, Matches.platformFor(sumo, "scrims"), List.of(List.of(a.player), List.of(b.player)));
            long deadline = System.currentTimeMillis() + 4000;
            while ((Game.of(a.player) == null || Game.of(b.player) == null) && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            assertNotNull(Game.of(b.player));
            String objective = "ht-" + game.code();
            w.sent.clear();
            Spectators.enter(game, w.player, a.player);
            deadline = System.currentTimeMillis() + 4000;
            while (w.sent(ScoreboardObjectivePacket.class).stream().noneMatch(o -> objective.equals(o.objectiveName()))
                    && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            for (int i = 0; i < 5; i++) MinecraftServer.getSchedulerManager().processTick();
            assertTrue(w.sent(ScoreboardObjectivePacket.class).stream().anyMatch(o -> objective.equals(o.objectiveName()) && o.mode() == 0),
                    "the spectator got the tab health objective");
            assertTrue(w.sent(UpdateScorePacket.class).stream().anyMatch(u -> objective.equals(u.objectiveName()) && u.entityName().equals("TabHpA")),
                    "a player's health lines up on their row");
            int mine = (int) Math.ceil(w.player.getHealth());
            assertTrue(w.sent(UpdateScorePacket.class).stream().anyMatch(u -> objective.equals(u.objectiveName())
                            && u.entityName().equals("TabHpWatch") && u.score() == mine),
                    "the spectator's own row carries their real health, never a stray 0");
        } finally {
            Spectators.exit(w.player);
            if (game != null && game.status() == Game.Status.OPEN) game.end(Outcome.draw());
            a.player.remove();
            b.player.remove();
            w.player.remove();
        }
    }
}
