package tennacle.looks;

import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The house tab heads with the network and says where the viewer stands under it. */
class HouseTabHeaderTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    private static String header(Player viewer) {
        return PlainTextComponentSerializer.plainText().serialize(Look.TENNACLE.tab().header().apply(viewer));
    }

    @Test
    void theHeaderNamesTheNetworkThenTheRoom() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(660.5, 65, 660.5), "TabHeadA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(662.5, 65, 662.5), "TabHeadB");
        Game game = null;
        try {
            String lobby = header(a.player);
            assertTrue(lobby.startsWith(Look.ADDRESS.toUpperCase(java.util.Locale.ROOT)), lobby);
            assertTrue(lobby.contains("online"), "nowhere in particular: the network's own count: " + lobby);

            Mode bridge = Matches.mode("bridge");
            assertNotNull(bridge);
            game = Matches.start(bridge, Matches.platformFor(null), List.of(List.of(a.player), List.of(b.player)));
            long deadline = System.currentTimeMillis() + 4000;
            while (Game.of(a.player) == null && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            assertNotNull(Game.of(a.player));

            String seated = header(a.player);
            assertTrue(seated.startsWith(Look.ADDRESS.toUpperCase(java.util.Locale.ROOT)), seated);
            assertTrue(seated.contains(bridge.title()), "the mode: " + seated);
            assertTrue(seated.contains(game.arena().meta().name()), "the map: " + seated);
            assertTrue(seated.contains(game.code()), "and the code that names it: " + seated);
            assertEquals(TextDecoration.State.NOT_SET,
                    Look.TENNACLE.tab().header().apply(a.player).decoration(TextDecoration.BOLD),
                    "the title alone is bold: a bold root carries into the line under it, which then overruns the panel");
        } finally {
            if (game != null && game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }
}
