package tennacle.looks;

import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;
import tennacle.games.bridge.Bridge;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.server.play.PlayerListHeaderAndFooterPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The house tab lists a seat's own counters, re-sent as they move, and only the ones the player picked. */
class HouseTabStatsTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    private static String text(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void theTabCountsTheSeat() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(620.5, 65, 620.5), "TabStatA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(622.5, 65, 622.5), "TabStatB");
        Game game = null;
        try {
            Mode bridge = Matches.mode("bridge");
            assertNotNull(bridge);
            game = Matches.start(bridge, Matches.platformFor(null), List.of(List.of(a.player), List.of(b.player)));
            long deadline = System.currentTimeMillis() + 4000;
            while (Game.of(a.player) == null && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            assertNotNull(Game.of(a.player));
            Function<Player, Component> footer = Matches.lookOf(game).tab().footer();
            assertTrue(text(footer.apply(a.player)).contains("Kills: 0 Deaths: 0 Goals: 0"), text(footer.apply(a.player)));

            a.sent.clear();
            Match.addSeat(game, a.player, Bridge.KILLS);
            Match.addSeat(game, b.player, Bridge.DEATHS);
            assertTrue(text(footer.apply(a.player)).contains("Kills: 1 Deaths: 0 Goals: 0"), "the viewer's own seat");
            assertTrue(text(footer.apply(b.player)).contains("Kills: 0 Deaths: 1 Goals: 0"), "never the other's");
            assertTrue(a.sent(PlayerListHeaderAndFooterPacket.class).stream().anyMatch(p -> text(p.footer()).contains("Kills: 1")),
                    "the change re-sent the tab");

            MinecraftServer.getCommandManager().execute(a.player, "tabstats goals kills");
            String picked = text(footer.apply(a.player));
            assertTrue(picked.contains("Goals: 0 Kills: 1"), "the pick, in the picked order: " + picked);
            assertFalse(picked.contains("Deaths"), "and nothing unpicked");
            MinecraftServer.getCommandManager().execute(a.player, "tabstats all");
            assertTrue(text(footer.apply(a.player)).contains("Kills: 1 Deaths: 0 Goals: 0"), "all puts the mode's list back");
        } finally {
            if (game != null) game.end(Outcome.draw());
            a.player.remove();
            b.player.remove();
        }
    }
}
