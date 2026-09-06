package tennacle.games.matchmaking;

import tennacle.games.watch.Spectators;
import tennacle.looks.Deaths;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.modules.flow.Countdown;
import tennacle.games.bridge.Cages;
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
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void aViewerHoldsTheEntryWhenTheSpawnArrives() throws InterruptedException {
        FakePlayer first = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "SeeFirst");
        FakePlayer late = FakePlayer.connect(instance, new Pos(2.5, 65, 2.5), "SeeLate");
        Game game = null;
        try {
            Mode sumo = Matches.mode("sumo");
            assertNotNull(sumo);
            game = Matches.start(sumo, Matches.platformFor(null), java.util.List.of(java.util.List.of(first.player)));
            long deadline = System.currentTimeMillis() + 4000;
            while (Game.of(first.player) == null && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            assertNotNull(Game.of(first.player), "the first player is bound: the cold, staggered shape of a first duel");
            first.sent.clear();
            assertTrue(game.join(late.player), "the second seat");
            deadline = System.currentTimeMillis() + 4000;
            while (Game.of(late.player) == null && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            assertNotNull(Game.of(late.player));
            for (int i = 0; i < 3; i++) MinecraftServer.getSchedulerManager().processTick(); // the bind's resync
            // what the client does with the sequence: a spawn is rendered only under an entry it holds
            boolean held = false, rendered = false, unlisted = false;
            int spawnAt = -1;
            java.util.List<net.minestom.server.network.packet.server.SendablePacket> packets = java.util.List.copyOf(first.sent);
            for (int i = 0; i < packets.size(); i++) {
                var packet = packets.get(i);
                if (packet instanceof net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket info
                        && info.actions().contains(net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket.Action.ADD_PLAYER)
                        && info.entries().stream().anyMatch(e -> e.uuid().equals(late.player.getUuid()))) held = true;
                if (packet instanceof net.minestom.server.network.packet.server.play.PlayerInfoRemovePacket remove
                        && remove.uuids().contains(late.player.getUuid())) {
                    held = false;
                    unlisted = true;
                }
                if (packet instanceof net.minestom.server.network.packet.server.play.SpawnEntityPacket spawn
                        && spawn.entityId() == late.player.getEntityId()) {
                    spawnAt = i;
                    rendered = held;
                }
            }
            assertTrue(spawnAt >= 0, "the newcomer was spawned to the player already there");
            assertTrue(rendered, "the viewer held the newcomer's entry when the spawn arrived (at " + spawnAt
                    + ") - a client drops a spawn it has no entry for, and the bind's entry a tick later cannot revive it");
            // the first duel's shape: one player binds while the other is still in flight. Unlisting the arrival
            // there drops the spawn its default viewable rule sends on landing, and the bind's entry never revives it
            assertFalse(unlisted, "a member never unlists a seated arrival during its transfer");
        } finally {
            if (game != null && game.status() == Game.Status.OPEN) game.end(Outcome.draw());
            first.player.remove();
            late.player.remove();
        }
    }

    @Test
    void bothPlayersLeavingEndsTheGame() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "GoneA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(2.5, 65, 2.5), "GoneB");
        Game game = null;
        try {
            MinecraftServer.getCommandManager().execute(a.player, "duel GoneB sumo");
            MinecraftServer.getCommandManager().execute(b.player, "duel accept GoneA");
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
    void spectatingASpectatorJoinsTheirGame() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "SpecTgtA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(2.5, 65, 2.5), "SpecTgtB");
        FakePlayer c = FakePlayer.connect(instance, new Pos(4.5, 65, 4.5), "SpecWatchC");
        FakePlayer d = FakePlayer.connect(instance, new Pos(6.5, 65, 6.5), "SpecWatchD");
        Game game = null;
        try {
            Mode sumo = Matches.mode("sumo");
            assertNotNull(sumo);
            game = Matches.start(sumo, Matches.platformFor(null), java.util.List.of(java.util.List.of(a.player), java.util.List.of(b.player)));
            long deadline = System.currentTimeMillis() + 4000;
            while ((Game.of(a.player) == null || Game.of(b.player) == null) && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            MinecraftServer.getCommandManager().execute(c.player, "spectate SpecTgtA");
            assertSame(game, Matches.grotto().spectating(c.player), "C watches A's game");
            MinecraftServer.getCommandManager().execute(d.player, "spectate SpecWatchC");
            assertSame(game, Matches.grotto().spectating(d.player), "D, asking for a spectator, lands in that spectator's game");
        } finally {
            Spectators.exit(c.player);
            Spectators.exit(d.player);
            if (game != null && game.status() == Game.Status.OPEN) game.end(Outcome.draw());
            a.player.remove();
            b.player.remove();
            c.player.remove();
            d.player.remove();
        }
    }

    @Test
    void deathCuesReachTheKillerAndTheDead() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "CueA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(2.5, 65, 2.5), "CueB");
        Game game = null;
        try {
            Mode sumo = Matches.mode("sumo");
            assertNotNull(sumo);
            game = Matches.start(sumo, Matches.platformFor(null),
                    java.util.List.of(java.util.List.of(a.player), java.util.List.of(b.player)));
            long deadline = System.currentTimeMillis() + 4000;
            while ((Game.of(a.player) == null || Game.of(b.player) == null) && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            assertNotNull(Game.of(b.player));
            var kill = net.kyori.adventure.sound.Sound.sound(net.minestom.server.sound.SoundEvent.ENTITY_EXPERIENCE_ORB_PICKUP,
                    net.kyori.adventure.sound.Sound.Source.MASTER, 1f, 2f);
            var died = net.kyori.adventure.sound.Sound.sound(net.minestom.server.sound.SoundEvent.ENTITY_PLAYER_DEATH,
                    net.kyori.adventure.sound.Sound.Source.MASTER, 1f, 1f);
            var cues = io.github.term4.canvas.theme.SoundSet.of()
                    .cue(io.github.term4.canvas.theme.SoundSet.KILL, kill)
                    .cue(io.github.term4.canvas.theme.SoundSet.DIED, died)
                    .cue(io.github.term4.canvas.theme.SoundSet.ENEMY_DOWN, kill);
            a.sent.clear();
            b.sent.clear();
            Deaths.cue(game, b.player, a.player, cues);
            var heardByKiller = a.sent(net.minestom.server.network.packet.server.play.SoundEffectPacket.class);
            var heardByDead = b.sent(net.minestom.server.network.packet.server.play.SoundEffectPacket.class);
            assertEquals(1, heardByKiller.size(), "the killer hears the kill cue, not the enemy-down one as well");
            assertEquals(net.minestom.server.sound.SoundEvent.ENTITY_EXPERIENCE_ORB_PICKUP.key(), heardByKiller.getFirst().soundEvent().key());
            assertEquals(1, heardByDead.size());
            assertEquals(net.minestom.server.sound.SoundEvent.ENTITY_PLAYER_DEATH.key(), heardByDead.getFirst().soundEvent().key());
        } finally {
            if (game != null && game.status() == Game.Status.OPEN) game.end(Outcome.draw());
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void aCageSecondsChangeMidCountMovesTheOpen() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "CageA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(2.5, 65, 2.5), "CageB");
        Game game = null;
        try {
            MinecraftServer.getCommandManager().execute(a.player, "duel CageB bridge");
            MinecraftServer.getCommandManager().execute(b.player, "duel accept CageA");
            long deadline = System.currentTimeMillis() + 4000;
            while ((Game.of(a.player) == null || Game.of(b.player) == null) && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            game = Game.of(a.player);
            assertNotNull(game, "the duel launched");
            assertTrue(Match.opening(game));
            game.state().set(Scope.GAME, Countdown.FROM, 30);
            long grace = game.rules().resolve(Scope.GAME, Countdown.GRACE);
            long interval = game.rules().resolve(Scope.GAME, Countdown.INTERVAL);
            for (int i = 0; i < grace + 2 * interval + 5; i++) MinecraftServer.getSchedulerManager().processTick();
            assertTrue(Match.opening(game), "a thirty-second count is still going");
            game.state().set(Scope.GAME, Countdown.FROM, 3); // under what is already shown: the next beat opens
            for (int i = 0; i < 2 * interval + 5; i++) MinecraftServer.getSchedulerManager().processTick();
            assertFalse(Match.opening(game), "the shortened count opened the match");
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
            MinecraftServer.getCommandManager().execute(b.player, "duel accept BGoneA");
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
            MinecraftServer.getCommandManager().execute(a.player, "duel accept DuelQueueB");
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
            MinecraftServer.getCommandManager().execute(b.player, "duel accept DuelA");
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
