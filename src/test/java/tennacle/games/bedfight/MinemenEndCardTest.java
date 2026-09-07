package tennacle.games.bedfight;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.Ruleset;
import tennacle.games.TestWiring;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Minemen's card: a kill count beside every name, the leader's apart, and the rematch it closes on. */
class MinemenEndCardTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void theCardCountsKillsAndOffersARematch() {
        Mode bedfight = Matches.mode("bedfight");
        Game game = Matches.launch(bedfight, Matches.platformFor("minemen"), BedFightRoundTest.fixture());
        FakePlayer top = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "MmcCardTop");
        FakePlayer rest = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "MmcCardRest");
        try {
            top.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            rest.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(top.player));
            assertTrue(game.join(rest.player));
            await("both seated", () -> Game.of(top.player) == game && Game.of(rest.player) == game);
            Matches.modify(game, Ruleset.named("linger").rule("flow.end.linger", "200"));

            Slot seat = game.roster().slotOf(top.player);
            assertNotNull(seat);
            game.state().set(Scope.slot(seat.index()), BedFight.KILLS, 3);
            top.sent.clear();
            game.decide(Outcome.winner(seat.team()), 1);
            long deadline = System.currentTimeMillis() + 8000;
            while (game.status() == Game.Status.OPEN && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                MinecraftServer.getSchedulerManager().processTickEnd();
            }
            for (int i = 0; i < 3; i++) { // the card lands at the end of the deciding tick
                MinecraftServer.getSchedulerManager().processTick();
                MinecraftServer.getSchedulerManager().processTickEnd();
            }

            List<SystemChatPacket> chat = top.sent(SystemChatPacket.class);
            List<String> plain = chat.stream()
                    .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
            int at = -1;
            for (int i = 0; i < plain.size(); i++) {
                if (plain.get(i).contains("Match Results")) { at = i; break; }
            }
            assertTrue(at >= 0, "the card lands: " + plain);
            String card = plain.get(at);
            assertTrue(card.contains("MmcCardTop (3)"), "the leader's kills ride their name: " + card);
            assertTrue(card.contains("MmcCardRest (0)"), "and so do everyone else's: " + card);
            assertTrue(card.contains("(REMATCH)"), "the card closes on the rematch: " + card);
            // the title is a gradient, so it carries no whole literal: take the card by its place, not its text
            String raw = GsonComponentSerializer.gson().serialize(chat.get(at).message());
            assertTrue(raw.contains("/rematch"), "which runs the command: " + raw);
            assertTrue(raw.contains("Kills: "), "and a name hovers its count: " + raw);
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            top.player.remove();
            rest.player.remove();
        }
    }
}
