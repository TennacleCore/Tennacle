package tennacle.games.bedwars;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Captured (public pregame, 2026-09-27): no team shows anywhere until the start, where a side lands together. */
class PregameTest extends HeadlessServerTest {

    private static final Pos ROOM = new Pos(0.5, 100, 0.5);

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path replays;

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        tennacle.glue.ReplayBridge.install();
        tennacle.games.watch.Replays.install(replays, new ShardManager());
    }

    @Test
    void teamsWaitForTheStart() {
        Mode doubles = Matches.mode("bedwars-doubles");
        Game game = Matches.launch(doubles, Matches.platformFor(null), BedWarsRoundTest.fixture());
        game.state().set(Scope.GAME, Countdown.START_AT, 99);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "PreA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "PreB");
        FakePlayer c = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "PreC");
        List<FakePlayer> all = List.of(a, b, c);
        try {
            for (FakePlayer p : all) p.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player, b.player)));
            assertTrue(Matches.enter(game, List.of(c.player)));
            await("all seated", () -> all.stream().allMatch(p -> Game.of(p.player) == game));
            for (FakePlayer p : all) assertNull(p.player.getDisplayName(), "no team color in the room");

            game.state().set(Scope.GAME, Countdown.START_AT, 3);
            BedWarsRoundTest.live(game);
            assertEquals(Match.teamOf(game, a.player), Match.teamOf(game, b.player), "a side lands together");
            for (FakePlayer p : all) {
                assertNotNull(p.player.getDisplayName(), "the team's color from the start");
                assertEquals(game.roster().team(Match.teamOf(game, p.player)).color(), p.player.getDisplayName().color());
            }
        } finally {
            tennacle.games.watch.Replays.discard(game);
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            for (FakePlayer p : all) p.player.remove();
        }
    }
}
