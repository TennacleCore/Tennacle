package tennacle.games.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A build rule written for one seat binds that seat alone: {@code /rules @player space.build.height 90}. */
class ScopedBuildTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    private static List<String> chat(FakePlayer p, String command) {
        p.sent.clear();
        MinecraftServer.getCommandManager().execute(p.player, command);
        return p.sent(SystemChatPacket.class).stream()
                .<String>map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }

    private static boolean refused(FakePlayer p, Game game, int x, int y) {
        var event = new PlayerBlockPlaceEvent(p.player, game.instance(), Block.WHITE_TERRACOTTA, BlockFace.TOP,
                new BlockVec(x, y, 1), new Pos(0.5, 1, 0.5), PlayerHand.MAIN);
        EventDispatcher.call(event);
        return event.isCancelled();
    }

    @Test
    void aSeatsOwnCeiling() {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "ScopeRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "ScopeBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            tennacle.Mechanics.polyp.clientInfo().setProtocol(red.player, 47); // 1.8 clients: the band is the rule here,
            tennacle.Mechanics.polyp.clientInfo().setProtocol(blue.player, 47); // not a modern client's placement reach
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            BridgeRoundTest.tickUntil(game, "GO", () -> Match.live(game));
            assertTrue(refused(red, game, -3, 80), "the marker tops the band at 72");

            List<String> sent = chat(red, "rules @ScopeRed space.build.height 90");
            assertTrue(sent.stream().anyMatch(l -> l.contains("@ScopeRed space.build.height = 90")), sent.toString());
            assertFalse(refused(red, game, -3, 80), "red's own ceiling rose");
            assertTrue(refused(blue, game, 3, 80), "blue's did not");

            List<String> teams = chat(red, "rules #" + Bridge.teamOf(game, blue.player) + " space.build.height 95");
            assertTrue(teams.stream().anyMatch(l -> l.contains("space.build.height = 95")), teams.toString());
            assertFalse(refused(blue, game, 3, 80), "a side's rule reaches its seat");
            assertTrue(chat(red, "rules @nobody space.build.height 1").stream().anyMatch(l -> l.contains("No player named nobody")));
            assertTrue(chat(red, "rules #9 space.build.height 1").stream().anyMatch(l -> l.contains("No team 9")));

            // the seat's override drops: the value above it shows through again, not the 90 the ledger still held
            assertEquals(90, game.rules().resolve(red.player, Rules.HEIGHT));
            List<String> dropped = chat(red, "rules @ScopeRed space.build.height unset");
            assertTrue(dropped.stream().anyMatch(l -> l.contains("space.build.height unset")), dropped.toString());
            assertEquals(game.rules().resolve(io.github.term4.grotto.state.Scope.GAME, Rules.HEIGHT),
                    game.rules().resolve(red.player, Rules.HEIGHT), "back on the game's value");
            assertTrue(chat(red, "rules @ScopeRed space.build.height unset").stream().anyMatch(l -> l.contains("Nothing is set")));
            game.decide(Outcome.draw(), 1);
        } finally {
            red.player.remove();
            blue.player.remove();
        }
    }
}
