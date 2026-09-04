package tennacle.games.bridge;

import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The cage holds a player inside a box the cage's own walls do not decide, so an unlocked cage is a look
 * rather than room to move. The whitelist and blacklist are the only say a game has over which cages it takes.
 */
class CageHoldTest extends HeadlessServerTest {

    private static final Pos SEAT = new Pos(10.5, 64, 20.5);

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    private Game bridge() {
        Mode bridge = Matches.mode("bridge");
        assertNotNull(bridge);
        Game game = Matches.launch(bridge, Matches.platformFor(bridge, null), bridge.arena().get());
        for (int i = 0; i < 3; i++) MinecraftServer.getSchedulerManager().processTick();
        return game;
    }

    @Test
    void seatFollowsTheRule() {
        Game game = bridge();
        try {
            Slot seat = game.roster().slot(0);
            assertNotNull(seat);
            assertEquals(seat.spawn(), Cages.seat(game, seat), "the cage marker by default");

            PosMarker respawn = game.arena().marker(Bridge.RESPAWN, seat.team());
            assertNotNull(respawn, "the map declares one per team");
            game.state().set(Scope.GAME, Cages.SEAT, Cages.SEAT_RESPAWN);
            assertEquals(respawn.pos(), Cages.seat(game, seat), "held where the team respawns instead");
        } finally {
            game.end(Outcome.aborted());
        }
    }

    @Test
    void boundedHoldsInsideTheBoxWhateverTheCageLooksLike() {
        Game game = bridge();
        try {
            assertEquals(3, game.rules().resolve(Scope.GAME, Cages.BOUNDS), "a three-block box by default");

            Pos inside = SEAT.add(0.4, 0, -0.4);
            assertSame(inside, Cages.hold(game, SEAT, inside), "a move that stays inside is left alone");

            Pos far = SEAT.add(9, 0, 9);
            Pos held = Cages.hold(game, SEAT, far);
            assertEquals(SEAT.x() + 3, held.x(), "clamped to the boundary, not dragged home");
            assertEquals(SEAT.z() + 3, held.z());

            assertEquals(SEAT.y() + 3, Cages.hold(game, SEAT, SEAT.add(0, 40, 0)).y(),
                    "and bounded upward too - a ceiling of blocks is no ceiling to a flier");
            assertEquals(SEAT.y() - 3, Cages.hold(game, SEAT, SEAT.sub(0, 40, 0)).y());

            game.state().set(Scope.GAME, Cages.BOUNDS, 4);
            assertEquals(SEAT.x() + 4, Cages.hold(game, SEAT, far).x(), "the box is a rule, live");

            game.state().set(Scope.GAME, Cages.BOUNDS, 0);
            assertEquals(SEAT.x(), Cages.hold(game, SEAT, far).x(), "a box of nothing holds them on the spot");
            Pos apex = SEAT.add(0, 1.25, 0);
            assertSame(apex, Cages.hold(game, SEAT, apex), "a jump still clears, even on the spot");

            game.state().set(Scope.GAME, Cages.HEIGHT, 0);
            assertEquals(SEAT.y(), Cages.hold(game, SEAT, apex).y(), "no height either, and the jump is a pin");

            game.state().set(Scope.GAME, Cages.BOUNDS, -1);
            assertSame(far, Cages.hold(game, SEAT, far), "below zero the walls hold them, not the server");
        } finally {
            game.end(Outcome.aborted());
        }
    }

    @Test
    void aGameTakesTheCagesItsRulesAllow() {
        Game game = bridge();
        try {
            assertEquals("tall", Cages.allowed(game, "tall"), "no lists: every pick stands");

            game.state().set(Scope.GAME, Cages.BLACKLIST, List.of("tall"));
            assertEquals("", Cages.allowed(game, "tall"), "a blacklisted pick falls back to one that is allowed");

            game.state().set(Scope.GAME, Cages.BLACKLIST, List.of());
            game.state().set(Scope.GAME, Cages.WHITELIST, List.of("default"));
            assertEquals("", Cages.allowed(game, "tall"), "outside the whitelist, same fallback");
            assertEquals("", Cages.allowed(game, ""), "the default cage is named 'default' in a list");
        } finally {
            game.end(Outcome.aborted());
        }
    }
}
