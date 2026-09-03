package tennacle.games;

import static org.junit.jupiter.api.Assertions.assertFalse;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.archipelago.testsupport.MechanicsBoot;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.sumo.Sumo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /duel} end-to-end: every request answers in chat, dueling back launches the game. */
class DuelCommandTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void bothPlayersLeavingEndsTheGame() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "GoneA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(2.5, 65, 2.5), "GoneB");
        Game game = null;
        try {
            MinecraftServer.getCommandManager().execute(a.player, "duel GoneB sumo");
            MinecraftServer.getCommandManager().execute(b.player, "duel GoneA");
            long deadline = System.currentTimeMillis() + 4000;
            while (Game.of(a.player) == null && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            game = Game.of(a.player);
            assertNotNull(game, "the duel launched");
            MinecraftServer.getCommandManager().execute(a.player, "spawn"); // /l
            MinecraftServer.getCommandManager().execute(b.player, "spawn");
            for (int i = 0; i < 5; i++) MinecraftServer.getSchedulerManager().processTick();
            assertNull(Game.of(a.player), "both left");
            assertNull(Game.of(b.player));
            assertEquals(0, game.members().size(), "nobody is in it");
            for (int i = 0; i < 800; i++) MinecraftServer.getSchedulerManager().processTick(); // the holds, then the linger
            assertNotEquals(Game.Status.OPEN, game.status(), "an emptied game ends instead of running forever");
        } finally {
            if (game != null && game.status() == Game.Status.OPEN) game.end(Outcome.draw());
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void bothPlayersLeavingEndsABridgeGame() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BGoneA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(2.5, 65, 2.5), "BGoneB");
        Game game = null;
        try {
            MinecraftServer.getCommandManager().execute(a.player, "duel BGoneB bridge");
            MinecraftServer.getCommandManager().execute(b.player, "duel BGoneA");
            long deadline = System.currentTimeMillis() + 4000;
            while (Game.of(a.player) == null && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            game = Game.of(a.player);
            assertNotNull(game, "the duel launched");
            MinecraftServer.getCommandManager().execute(a.player, "spawn"); // /l
            MinecraftServer.getCommandManager().execute(b.player, "spawn");
            for (int i = 0; i < 5; i++) MinecraftServer.getSchedulerManager().processTick();
            assertNull(Game.of(a.player), "both left");
            assertNull(Game.of(b.player));
            assertEquals(0, game.members().size(), "nobody is in it");
            for (int i = 0; i < 800; i++) MinecraftServer.getSchedulerManager().processTick(); // the holds, then the linger
            assertNotEquals(Game.Status.OPEN, game.status(), "an emptied game ends instead of running forever");
        } finally {
            if (game != null && game.status() == Game.Status.OPEN) game.end(Outcome.draw());
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void aQueuedPlayerSeatedByADuelLeavesTheQueue() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "DuelQueueA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(2.5, 65, 2.5), "DuelQueueB");
        FakePlayer c = FakePlayer.connect(instance, new Pos(4.5, 65, 4.5), "DuelQueueC");
        try {
            MinecraftServer.getCommandManager().execute(a.player, "queue sumo");
            assertTrue(Queues.waiting(a.player), "queued");
            MinecraftServer.getCommandManager().execute(b.player, "duel DuelQueueA sumo");
            MinecraftServer.getCommandManager().execute(a.player, "duel DuelQueueB");
            long deadline = System.currentTimeMillis() + 4000;
            while (Game.of(a.player) == null && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            assertNotNull(Game.of(a.player), "the duel seated the queued player");
            assertFalse(Queues.waiting(a.player), "and the seat took them out of the queue");
            MinecraftServer.getCommandManager().execute(c.player, "queue sumo");
            assertTrue(Queues.waiting(c.player), "a later queuer waits instead of pairing with a seated player");
            assertNull(Game.of(c.player));
        } finally {
            Game game = Game.of(a.player) != null ? Game.of(a.player) : Game.of(b.player);
            if (game != null) game.end(Outcome.draw());
            MinecraftServer.getCommandManager().execute(c.player, "queue leave");
            a.player.remove();
            b.player.remove();
            c.player.remove();
        }
    }

    @Test
    void requestAnswersAndAcceptLaunches() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "DuelA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(2.5, 65, 2.5), "DuelB");
        try {
            a.sent.clear();
            MinecraftServer.getCommandManager().execute(a.player, "duel DuelB sumo");
            assertTrue(a.sent.stream().anyMatch(p -> p instanceof SystemChatPacket),
                    "the requester always hears back");

            java.time.LocalDate boardDate = java.time.LocalDate.now(); // what the board prints at launch
            MinecraftServer.getCommandManager().execute(b.player, "duel DuelA");
            long deadline = System.currentTimeMillis() + 4000;
            while (Game.of(a.player) == null && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick(); // the join bind lands on the next tick
                Thread.sleep(10);
            }
            assertNotNull(Game.of(a.player), "the duel launched and seated the requester");
            assertNotNull(Game.of(b.player), "and the accepter");

            // the server convention: UUID id, 5-char board code, and only the server re-derives one from the other
            Game launched = Game.of(a.player);
            assertEquals(launched.id(), java.util.UUID.fromString(launched.id()).toString());
            assertEquals(5, launched.code().length());
            assertEquals(launched.id(), tennacle.GameIds.derive(boardDate, launched.code()),
                    "the board date + code re-derive the id server-side");
            assertEquals(launched.id(), tennacle.GameIds.derive(boardDate,
                            launched.code().toUpperCase().replace('0', 'O').replace('1', 'I')),
                    "a squinted screenshot code still derives");

            // the live-faithful spectate path: a watcher from the lobby world follows the game
            FakePlayer w = FakePlayer.connect(instance, new Pos(4.5, 65, 4.5), "DuelWatch");
            Game game = Game.of(a.player);
            game.spectate(w.player);
            deadline = System.currentTimeMillis() + 4000;
            while ((!a.player.getViewers().contains(w.player) || w.player.getInstance() != game.instance())
                    && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            assertTrue(w.player.getInstance() == game.instance(), "the watcher transferred");
            assertTrue(a.player.getViewers().contains(w.player), "the watcher sees the fighters");
            w.player.remove();

            // /spawn mid-game: the seated player departs and lands back in the lobby
            MinecraftServer.getCommandManager().execute(a.player, "spawn");
            deadline = System.currentTimeMillis() + 4000;
            while ((Game.of(a.player) != null || a.player.getInstance() != instance)
                    && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            assertNull(Game.of(a.player), "the departing player left the game");
            assertTrue(a.player.getInstance() == instance, "and returned to the lobby");
        } finally {
            Game game = Game.of(a.player) != null ? Game.of(a.player) : Game.of(b.player);
            if (game != null && game.status() == Game.Status.OPEN) game.end(Outcome.draw());
            a.player.remove();
            b.player.remove();
        }
    }
}
