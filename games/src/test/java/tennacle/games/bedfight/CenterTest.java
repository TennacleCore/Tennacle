package tennacle.games.bedfight;

import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The line a map is symmetric about, and which half of it somebody is standing in. */
class CenterTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void aMapWithNoCenterReadsFromTheOrigin() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor(null), BedFightRoundTest.fixture());
        try {
            assertEquals(Vec.ZERO, tennacle.games.map.Center.of(game), "no center region: the origin is the middle");
        } finally {
            game.end(Outcome.aborted());
        }
    }

    /** The fixture seats team 1 at x=-4.5 and team 2 at x=5.5, so the sides divide along x. */
    @Test
    void aSideIsTheOneWhoseSpawnSitsThatWay() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor(null), BedFightRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "CenterRed");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            await("seated", () -> Game.of(red.player) == game);

            String mine = tennacle.games.Match.teamOf(game, red.player);
            assertEquals(mine, tennacle.games.map.Center.sideOf(game, new Vec(-8, 65, 0)), "deep on their own side");
            assertEquals(mine.equals("1") ? "2" : "1", tennacle.games.map.Center.sideOf(game, new Vec(8, 65, 0)), "and the far one");
            assertNull(tennacle.games.map.Center.sideOf(game, new Vec(0, 65, 0)), "the line itself is neither");

            red.player.teleport(new Pos(-8.5, 65, 0.5)).join();
            assertFalse(tennacle.games.map.Center.pushing(game, red.player), "at home, pushing nothing");
            red.player.teleport(new Pos(8.5, 65, 0.5)).join();
            assertTrue(tennacle.games.map.Center.pushing(game, red.player), "over the line is pressure");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
        }
    }
}
