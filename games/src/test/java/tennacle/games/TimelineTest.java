package tennacle.games;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameType;
import io.github.term4.grotto.Markers;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.modules.flow.Phases;
import io.github.term4.grotto.roster.SlotLayout;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Codec;
import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Events fire at their offsets into the live phase, a rule moves one, and the board reads the next. */
class TimelineTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void eventsFireInOrderAndTheBoardReadsTheNext() {
        List<String> heard = new ArrayList<>();
        GameType type = GameType.builder("timeline-test").slots(SlotLayout.groups(Markers.SPAWN, 1))
                .rule(Countdown.FROM, 1).rule(Countdown.GRACE, 1L).rule(Countdown.INTERVAL, 1L)
                .modules(() -> Phases.of(Match.OPENING, Match.LIVE, Match.OVER),
                        Countdown::of,
                        () -> Timeline.of()
                                .event("a", "Alpha", 40).on("a", g -> heard.add("a"))
                                .event("b", "Beta", 400).on("b", g -> heard.add("b"))
                                .event("quiet", null, 60).on("quiet", g -> heard.add("quiet"))
                                .watched("w", "Watched", g -> 1000))
                .build();
        Game game = Matches.grotto().launch(type, Matches.mode("bedfight").arena().get());
        FakePlayer p = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "TimeA");
        try {
            p.player.setInstance(game.instance(), game.arena().meta().spawn()).join();
            assertTrue(game.join(p.player));
            tickUntil(game, "live", () -> Match.live(game));
            Timeline timeline = game.module(Timeline.class);
            assertNotNull(timeline);
            Timeline.Next next = timeline.next();
            assertNotNull(next);
            assertEquals("Alpha", next.label());
            assertTrue(next.left() <= 40 && next.left() > 0, "counting down to it: " + next.left());

            tickUntil(game, "Alpha", () -> heard.contains("a"));
            assertEquals(List.of("a"), heard);
            assertEquals("Beta", timeline.next().label(), "the quiet one never shows");

            game.state().set(Scope.GAME, StateKey.of("timeline.b", Codec.LONG, 400L), 50L); // a rule moves it up
            tickUntil(game, "Beta, moved", () -> heard.contains("b"));
            tickUntil(game, "the quiet one", () -> heard.contains("quiet"));
            assertEquals(List.of("a", "b", "quiet"), heard);
            assertEquals("Watched", timeline.next().label(), "the one another module fires is all that is left");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            p.player.remove();
        }
    }

    static void tickUntil(Game game, String what, Supplier<Boolean> done) {
        long deadline = System.currentTimeMillis() + 8000;
        while (!done.get() && System.currentTimeMillis() < deadline) {
            MinecraftServer.getSchedulerManager().processTick();
            MinecraftServer.getSchedulerManager().processTickEnd();
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertTrue(done.get(), what);
    }
}
