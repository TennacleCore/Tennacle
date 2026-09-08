package tennacle.games.bridge;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.archipelago.shard.ShardView;
import io.github.term4.grotto.Outcome;
import io.github.term4.polyp.platform.player.PlayerNames;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.watch.Spectators;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A game's players complete for /spectate, which no tab scope would ever offer, and for the viewer once inside. */
class SpectateNamesTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        tennacle.games.TestWiring.ensure(instance);
    }

    @Test
    void aGameCompletesForSpectate() {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "NamesRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "NamesBlue");
        FakePlayer out = FakePlayer.connect(instance, new Pos(4.5, 65, 0.5), "NamesWatch");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);

            List<String> tab = PlayerNames.of(out.player);
            assertFalse(tab.contains("NamesRed"), "a lobby tab does not reach into the game: " + tab);
            assertTrue(Spectators.watchable().containsAll(List.of("NamesRed", "NamesBlue")),
                    "but /spectate offers them: " + Spectators.watchable());

            Spectators.enter(game, out.player, red.player);
            BridgeRoundTest.tickUntil(game, "the view lands",
                    () -> ShardView.of(out.player).blocks() == game.shard());
            assertTrue(PlayerNames.of(out.player).containsAll(List.of("NamesRed", "NamesBlue")),
                    "and a viewer completes whom they watch: " + PlayerNames.of(out.player));

            Spectators.exit(out.player);
            Matches.toLobby(out.player);
            game.decide(Outcome.draw(), 1);
            BridgeRoundTest.tickUntil(game, "the game ends", () -> game.status() != Game.Status.OPEN);
        } finally {
            red.player.remove();
            blue.player.remove();
            out.player.remove();
        }
    }

    /** Off the map, a watcher comes back where the side they follow respawns - not where it cages. */
    @Test
    void aWatcherLandsOnTheRespawn() {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "RecallRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "RecallBlue");
        FakePlayer out = FakePlayer.connect(instance, new Pos(4.5, 65, 0.5), "RecallWatch");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);

            Spectators.enter(game, out.player, red.player);
            BridgeRoundTest.tickUntil(game, "the view lands",
                    () -> ShardView.of(out.player).blocks() == game.shard());
            String side = Bridge.teamOf(game, red.player);
            Pos respawn = game.arena().marker(Bridge.RESPAWN, side).pos();
            assertNotEquals(respawn.x(), game.arena().marker(Bridge.CAGE, side).pos().x(),
                    "the fixture keeps the two markers apart");

            out.player.teleport(new Pos(0.5, 40, 0.5)).join();
            EventDispatcher.call(new PlayerMoveEvent(out.player, new Pos(0.5, 40, 0.5), true));
            BridgeRoundTest.tickUntil(game, "recalled to the side they follow",
                    () -> Math.abs(out.player.getPosition().x() - respawn.x()) < 0.01);

            Spectators.exit(out.player);
            Matches.toLobby(out.player);
            game.decide(Outcome.draw(), 1);
            BridgeRoundTest.tickUntil(game, "the game ends", () -> game.status() != Game.Status.OPEN);
        } finally {
            red.player.remove();
            blue.player.remove();
            out.player.remove();
        }
    }
}
