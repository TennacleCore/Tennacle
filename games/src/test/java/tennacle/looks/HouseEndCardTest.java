package tennacle.looks;

import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Ruleset;
import tennacle.games.TestWiring;
import tennacle.games.boxing.Boxing;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.outcome.Score;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The house end card: scrims' card, and a name click opens the frozen record without minemen's own line. */
class HouseEndCardTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    private static void awaitTicking(String what, Supplier<Boolean> done) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 4000;
        while (!done.get() && System.currentTimeMillis() < deadline) {
            MinecraftServer.getSchedulerManager().processTick();
            MinecraftServer.getSchedulerManager().processTickEnd();
            Thread.sleep(10);
        }
        assertTrue(done.get(), what);
    }

    @Test
    void namesOpenTheRecord() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(700.5, 65, 700.5), "CardWin");
        FakePlayer b = FakePlayer.connect(instance, new Pos(702.5, 65, 702.5), "CardLose");
        Game game = null;
        try {
            MinecraftServer.getCommandManager().execute(a.player, "duel CardLose boxing");
            MinecraftServer.getCommandManager().execute(b.player, "duel accept CardWin");
            awaitTicking("the duel seated both", () -> Game.of(a.player) != null && Game.of(b.player) != null);
            game = Game.of(a.player);
            assertNotNull(game);
            Matches.modify(game, Ruleset.named("linger").rule("flow.end.linger", "200"));
            a.sent.clear();

            Slot seat = game.roster().slotOf(a.player);
            assertNotNull(seat);
            game.state().set(Scope.slot(seat.index()), Boxing.HITS, game.rules().resolve(seat, Score.TARGET));
            Game decided = game;
            awaitTicking("the score decided it", () -> decided.decided() != null);
            for (int i = 0; i < 3; i++) { // the card lands at the end of the deciding tick
                MinecraftServer.getSchedulerManager().processTick();
                MinecraftServer.getSchedulerManager().processTickEnd();
            }

            List<String> lines = a.sent(SystemChatPacket.class).stream()
                    .map(c -> GsonComponentSerializer.gson().serialize(c.message())).toList();
            assertTrue(lines.stream().anyMatch(l -> l.contains("/results ") && l.contains("Click to view")),
                    "a name on the card opens its record: " + lines);
            assertFalse(lines.stream().anyMatch(l -> l.contains("Match Results")), "and minemen's own line stays quiet");
        } finally {
            if (game != null && game.status() == Game.Status.OPEN) game.end(Outcome.draw());
            a.player.remove();
            b.player.remove();
        }
    }
}
