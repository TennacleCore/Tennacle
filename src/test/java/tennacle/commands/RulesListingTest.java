package tennacle.commands;

import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /rules <package>} narrows the listing the way {@code /rules paths <path>} walks the mechanics tree. */
class RulesListingTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    private static List<String> lines(FakePlayer p, String command) {
        p.sent.clear();
        MinecraftServer.getCommandManager().execute(p.player, command);
        return p.sent(SystemChatPacket.class).stream()
                .<String>map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }

    @Test
    void aPackageNarrowsTheListing() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(300.5, 65, 300.5), "RuleListA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(302.5, 65, 302.5), "RuleListB");
        Game game = null;
        try {
            Mode bridge = Matches.mode("bridge");
            assertNotNull(bridge);
            game = Matches.start(bridge, Matches.platformFor(bridge, "scrims"),
                    List.of(List.of(a.player), List.of(b.player)));
            long deadline = System.currentTimeMillis() + 4000;
            while (Game.of(a.player) == null && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            assertNotNull(Game.of(a.player));

            List<String> flow = lines(a, "rules flow");
            assertTrue(flow.stream().anyMatch(l -> l.contains("flow.time.limit")), "the package lists its own");
            assertTrue(flow.stream().noneMatch(l -> l.contains("combat.")), "and nothing else: " + flow);
            assertTrue(flow.stream().anyMatch(l -> l.contains("narrow:") && l.contains("flow.countdown")
                    && l.contains("flow.time")), "with the way further in: " + flow);

            assertTrue(lines(a, "rules flow.time").stream().noneMatch(l -> l.contains("flow.countdown.from")),
                    "a deeper package narrows again");
            assertTrue(lines(a, "rules view.health.display").stream().anyMatch(l -> l.contains("view.health.display =")),
                    "a whole id with no value reads it back");
            assertTrue(lines(a, "rules nosuchpackage").stream().anyMatch(l -> l.contains("no rule, team or player")),
                    "and a name that is none of the three says so");
        } finally {
            if (game != null) game.end(Outcome.draw());
            a.player.remove();
            b.player.remove();
        }
    }
}
