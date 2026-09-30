package tennacle.games.bedwars;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.shard.ShardTabList;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.world.WorldPolicy;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.network.packet.server.play.PlayerInfoRemovePacket;
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;
import tennacle.games.beds.Lives;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Back in the lobby, a player is a plain body again, for themselves and for every client (the user, 2026-09-28). */
class LobbyReturnTest extends HeadlessServerTest {

    private static final Pos ROOM = new Pos(0.5, 100, 0.5);

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path replays;

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        tennacle.glue.ReplayBridge.install();
        tennacle.games.watch.Replays.install(replays, new ShardManager());
    }

    @FunctionalInterface
    private interface Body {
        void run(Game game, FakePlayer a, FakePlayer b);
    }

    /** A /spectate still on its way in when the game closes: the watch took their row off every lobby tab. */
    @Test
    void aWatchCutShortRelistsThem() {
        round("CutA", (game, fa, fb) -> {
            FakePlayer watcher = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "CutWatch");
            FakePlayer lobby = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "CutLobby");
            try {
                lobby.sent.clear();
                game.spectate(watcher.player, new Pos(4000.5, 65, 4000.5)); // an unloaded landing: the move stays in flight
                game.end(Outcome.aborted());
                assertEquals(instance, watcher.player.getInstance(), "closed before the move landed");
                assertTrue(listedLast(lobby, watcher.player.getUuid()), "a 1.8 client drops the spawn of a player it holds no row for");
            } finally {
                watcher.player.remove();
                lobby.player.remove();
            }
        });
    }

    // whether the last tab word the viewer got about the subject was a listing rather than a removal
    private static boolean listedLast(FakePlayer viewer, UUID subject) {
        Boolean listed = null;
        for (var packet : viewer.sent(net.minestom.server.network.packet.server.ServerPacket.class)) {
            if (packet instanceof PlayerInfoRemovePacket remove && remove.uuids().contains(subject)) listed = false;
            if (packet instanceof PlayerInfoUpdatePacket update && update.actions().contains(PlayerInfoUpdatePacket.Action.ADD_PLAYER)
                    && update.entries().stream().anyMatch(e -> e.uuid().equals(subject))) listed = true;
        }
        return Boolean.TRUE.equals(listed);
    }

    /** Minestom sends no spawn for a same-instance return, and the spawn is what re-lists a player. */
    @Test
    void aLobbyReturnResendsTheRow() {
        FakePlayer returning = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "RowBack");
        FakePlayer lobby = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "RowLobby");
        try {
            returning.player.setTag(ShardTabList.SPECTATOR, true);
            ShardTabList.resync(returning.player);
            returning.player.removeTag(ShardTabList.SPECTATOR); // cleared with no re-send
            lobby.sent.clear();
            Matches.toLobby(returning.player);
            assertEquals(GameMode.SURVIVAL, lastMode(lobby, returning.player.getUuid()));
        } finally {
            returning.player.remove();
            lobby.player.remove();
        }
    }

    /** A dead seat waiting on its respawn walks out with /l as a plain body. */
    @Test
    void aGhostLeavesClean() {
        round("GhostA", (game, fa, fb) -> {
            FakePlayer lobby = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "GhostLobby");
            try {
                game.module(Lives.class).fell(fb.player);
                assertTrue(game.isGhost(fb.player), "waiting to respawn");
                game.depart(fb.player);
                Matches.toLobby(fb.player);
                BedWarsRoundTest.tickUntil(game, "home", () -> fb.player.getInstance() == instance);
                assertFalse(fb.player.isInvisible());
                assertTrue(fb.player.getActiveEffects().isEmpty(), fb.player.getActiveEffects().toString());
                assertFalse(fb.player.isFlying());
                assertEquals(GameMode.SURVIVAL, fb.player.getGameMode());
                assertTrue(WorldPolicy.canSee(lobby.player, fb.player), "seen");
                assertTrue(WorldPolicy.canAffect(lobby.player, fb.player), "and hittable");
            } finally {
                lobby.player.remove();
            }
        });
    }

    private static GameMode lastMode(FakePlayer viewer, UUID subject) {
        GameMode last = null;
        for (PlayerInfoUpdatePacket packet : viewer.sent(PlayerInfoUpdatePacket.class)) {
            if (!packet.actions().contains(PlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE)) continue;
            for (PlayerInfoUpdatePacket.Entry entry : packet.entries()) if (entry.uuid().equals(subject)) last = entry.gameMode();
        }
        return last;
    }

    private static void round(String name, Body body) {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = BedWarsRoundTest.byArrival(Matches.launch(solo, Matches.platformFor(null), BedWarsRoundTest.fixture()));
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), name);
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), name + "F");
        try {
            a.player.setInstance(game.instance(), ROOM).join();
            b.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player)));
            assertTrue(Matches.enter(game, List.of(b.player)));
            await("both seated", () -> Game.of(a.player) == game && Game.of(b.player) == game);
            BedWarsRoundTest.live(game);
            body.run(game, a, b);
        } finally {
            tennacle.games.watch.Replays.discard(game);
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }
}
