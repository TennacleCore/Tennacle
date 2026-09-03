package tennacle.games;

import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket;
import net.minestom.server.entity.GameMode;
import io.github.term4.archipelago.shard.ShardView;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A spectator gets the tab health column: every player's row, and their own row reading 0. */
class SpectatorTabHealthTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void aSpectatorSeesThePlayersHealthAndAZeroOnTheirOwnRow() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "TabHpA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(2.5, 65, 2.5), "TabHpB");
        FakePlayer w = FakePlayer.connect(instance, new Pos(4.5, 65, 4.5), "TabHpWatch");
        FakePlayer x = FakePlayer.connect(instance, new Pos(6.5, 65, 6.5), "TabHpWatchX");
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
            assertTrue(w.sent(UpdateScorePacket.class).stream().anyMatch(u -> objective.equals(u.objectiveName())
                            && u.entityName().equals("TabHpWatch") && u.score() == 0),
                    "the spectator's own row reads 0: their health is nobody's business here");
            assertTrue(w.sent(UpdateScorePacket.class).stream().noneMatch(u -> objective.equals(u.objectiveName())
                            && u.entityName().equals("TabHpWatch") && u.score() != 0),
                    "and never their real health");

            // a second spectator: the first must see them - their entry NOT flagged spectator, their row 0
            w.sent.clear();
            Spectators.enter(game, x.player, a.player);
            deadline = System.currentTimeMillis() + 4000;
            while (Matches.grotto().spectating(x.player) != game && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            for (int i = 0; i < 5; i++) MinecraftServer.getSchedulerManager().processTick();
            assertTrue(ShardView.of(w.player).seesEntity(x.player), "co-spectators see each other's bodies");
            // the client drops a spawn it holds no tab entry for: the co-spectator's entry must land first
            int entryAt = -1, spawnAt = -1;
            var packets = java.util.List.copyOf(w.sent);
            for (int i = 0; i < packets.size(); i++) {
                var packet = packets.get(i);
                if (entryAt < 0 && packet instanceof PlayerInfoUpdatePacket info
                        && info.actions().contains(PlayerInfoUpdatePacket.Action.ADD_PLAYER)
                        && info.entries().stream().anyMatch(e -> e.uuid().equals(x.player.getUuid()))) entryAt = i;
                if (spawnAt < 0 && packet instanceof net.minestom.server.network.packet.server.play.SpawnEntityPacket spawn
                        && spawn.entityId() == x.player.getEntityId()) spawnAt = i;
            }
            assertTrue(spawnAt >= 0, "the second spectator spawned to the first");
            assertTrue(entryAt >= 0 && entryAt < spawnAt, "listed before spawned (entry at " + entryAt + ", spawn at " + spawnAt + ")");
            var wSees = w.sent(PlayerInfoUpdatePacket.class).stream()
                    .filter(p -> p.actions().contains(PlayerInfoUpdatePacket.Action.ADD_PLAYER))
                    .flatMap(p -> p.entries().stream()).filter(e -> e.uuid().equals(x.player.getUuid())).toList();
            assertTrue(wSees.getLast().gameMode() == GameMode.SPECTATOR,
                    "flagged spectator to a co-spectator too: drawn as a head, sunk, greyed, and its score skipped");
            assertTrue(w.sent(UpdateScorePacket.class).stream().noneMatch(u -> objective.equals(u.objectiveName())
                            && u.entityName().equals("TabHpWatchX")), "no score is sent for a flagged row");
        } finally {
            Spectators.exit(w.player);
            Spectators.exit(x.player);
            if (game != null && game.status() == Game.Status.OPEN) game.end(Outcome.draw());
            a.player.remove();
            b.player.remove();
            w.player.remove();
            x.player.remove();
        }
    }
}
