package tennacle.games.bridge;

import tennacle.bridge.ReplayBridge;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.Replays;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A game records itself, lands as a file when it ends, and tells its players where to watch it. */
class ReplaysTest extends HeadlessServerTest {

    @TempDir
    static Path replays;

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        ReplayBridge.install(); // the recording sources the server wires at boot
        Replays.install(replays, new io.github.term4.archipelago.shard.ShardManager());
    }

    private static boolean said(FakePlayer p, String line) {
        return p.sent(SystemChatPacket.class).stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message()))
                .anyMatch(l -> l.contains(line));
    }

    @Test
    void theClockReads() {
        assertEquals("15:02", Replays.clock(15 * 60 * 20 + 2 * 20));
        assertEquals("0:00", Replays.clock(0));
    }

    @Test
    void aGameLandsAsAFileAndSaysSo() {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "RecRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "RecBlue");
        try {
            assertTrue(Replays.recording(game), "recording from the launch");
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            BridgeRoundTest.tickUntil(game, "GO", () -> Match.live(game));
            game.depart(red.player); // gone before the end: still told
            red.sent.clear();
            blue.sent.clear();
            game.decide(Outcome.draw(), 1);
            BridgeRoundTest.tickUntil(game, "the game ends", () -> game.status() != Game.Status.OPEN);
            Path file = replays.resolve(game.id() + ".shard");
            await("the file lands", () -> Files.exists(file));
            await("the players hear it", () -> said(red, "This game has been recorded") && said(blue, "This game has been recorded"));
            String json = blue.sent(SystemChatPacket.class).stream()
                    .map(c -> GsonComponentSerializer.gson().serialize(c.message()))
                    .filter(l -> l.contains("recorded")).findFirst().orElseThrow();
            assertTrue(json.contains("copy_to_clipboard") && json.contains("/replay watch " + game.id()),
                    "the click copies the watch command with the full id: " + json);
            assertTrue(json.contains("bridge-fixture"), "the hover names the map: " + json);
            await("the spool is gone", () -> !Files.exists(replays.resolve("spool").resolve(game.id() + ".spool")));
        } finally {
            red.player.remove();
            blue.player.remove();
        }
    }
}
