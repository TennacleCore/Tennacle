package tennacle.games.bedfight;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.SetTitleTextPacket;
import net.minestom.server.network.packet.server.play.SetTitleTimePacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;
import tennacle.looks.Presentation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The opener is a title and a sound, not a chat line alone - each network's own, off its capture. */
class CountdownVoiceTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void everyBeatShowsItsNetworksTitle() {
        Mode bedfight = Matches.mode("bedfight");
        Game mmc = Matches.launch(bedfight, Matches.platformFor("minemen"), BedFightRoundTest.fixture());
        Game hpx = Matches.launch(bedfight, Matches.platformFor("hypixel"), BedFightRoundTest.fixture());
        FakePlayer p = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "CountWatch");
        FakePlayer q = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "CountWatchTwo");
        try {
            p.player.setInstance(mmc.instance(), new Pos(-4.5, 65, 0.5)).join();
            assertTrue(mmc.join(p.player));
            await("seated on minemen", () -> Game.of(p.player) == mmc);

            p.sent.clear();
            Presentation.voice(mmc).beat(mmc, 5);
            assertEquals(List.of("5"), shown(p), "the digit rides a title, not the chat line alone");
            Component digit = p.sent(SetTitleTextPacket.class).getFirst().title();
            assertEquals(NamedTextColor.YELLOW, digit.color(), "captured minemen: yellow");
            assertTrue(digit.hasDecoration(TextDecoration.BOLD), "and bold");
            assertTrue(p.sent(SetTitleTimePacket.class).stream().anyMatch(t -> t.stay() == 40),
                    "held the captured 40 ticks, not hypixel's 20");

            q.player.setInstance(hpx.instance(), new Pos(-4.5, 65, 0.5)).join();
            assertTrue(hpx.join(q.player));
            await("seated on hypixel", () -> Game.of(q.player) == hpx);
            q.sent.clear();
            Presentation.voice(hpx).beat(hpx, 3);
            assertEquals(List.of("3"), shown(q), "hypixel counts on a title too");
            assertEquals(NamedTextColor.RED, q.sent(SetTitleTextPacket.class).getFirst().title().color(),
                    "captured: red from three down");
            assertTrue(q.sent(SetTitleTimePacket.class).stream().anyMatch(t -> t.stay() == 20 && t.fadeOut() == 20),
                    "on its own captured timing");
        } finally {
            mmc.end(Outcome.aborted());
            hpx.end(Outcome.aborted());
            p.player.remove();
            q.player.remove();
        }
    }

    private static List<String> shown(FakePlayer p) {
        return p.sent(SetTitleTextPacket.class).stream()
                .map(t -> PlainTextComponentSerializer.plainText().serialize(t.title())).toList();
    }
}
