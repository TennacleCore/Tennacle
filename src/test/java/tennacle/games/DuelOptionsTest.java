package tennacle.games;

import tennacle.games.bridge.Cages;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.TimeLimit;
import io.github.term4.grotto.state.Scope;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.client.play.ClientTabCompletePacket;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import net.minestom.server.network.packet.server.play.TabCompletePacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /duel <player> <mode> [map] [-flag ...]}: the tail rides the request, is checked before it is sent, and completes. */
class DuelOptionsTest extends HeadlessServerTest {

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

    private static List<String> complete(FakePlayer p, String text) {
        p.sent.clear();
        MinecraftServer.getPacketListenerManager().processClientPacket(new ClientTabCompletePacket(3, text), p.player.getPlayerConnection());
        return p.sent(TabCompletePacket.class).stream().flatMap(t -> t.matches().stream()).map(m -> m.match()).toList();
    }

    private static Game await(FakePlayer p) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 4000;
        while (Game.of(p.player) == null && System.currentTimeMillis() < deadline) {
            MinecraftServer.getSchedulerManager().processTick();
            Thread.sleep(10);
        }
        return Game.of(p.player);
    }

    @Test
    void flagsRideTheRequest() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(500.5, 65, 500.5), "OptA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(502.5, 65, 502.5), "OptB");
        Game game = null;
        try {
            List<String> sent = chat(a, "duel OptB bridge -look=scrims -flow.time.limit=1200 -cage.blocks=false");
            assertTrue(sent.stream().anyMatch(l -> l.contains("request sent")), "the request went out: " + sent);
            MinecraftServer.getCommandManager().execute(b.player, "duel OptA");
            game = await(a);
            assertNotNull(game, "the accept launched");
            assertEquals("scrims", Matches.lookNameOf(game), "the flag's look, not the platform's");
            assertEquals(1200L, game.rules().resolve(Scope.GAME, TimeLimit.LIMIT), "a rule flag is seeded");
            assertFalse(game.rules().resolve(Scope.GAME, Cages.BLOCKS));
            Matches.modify(game, Ruleset.named("later").rule(TimeLimit.LIMIT, 600L));
            assertEquals(600L, game.rules().resolve(Scope.GAME, TimeLimit.LIMIT), "and /rules still layers over it");
            assertFalse(game.rules().resolve(Scope.GAME, Cages.BLOCKS), "without dropping the other flags");
        } finally {
            if (game != null) game.end(Outcome.draw());
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void aBadWordAnswersTheRequester() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(504.5, 65, 504.5), "OptC");
        FakePlayer b = FakePlayer.connect(instance, new Pos(506.5, 65, 506.5), "OptD");
        try {
            assertTrue(chat(a, "duel OptD sumo -nope").stream().anyMatch(l -> l.contains("unknown flag")));
            assertTrue(chat(a, "duel OptD sumo nosuchmap").stream().anyMatch(l -> l.contains("no map named")));
            assertTrue(chat(a, "duel OptD sumo -look=nosuchlook").stream().anyMatch(l -> l.contains("no look named")));
            assertTrue(chat(a, "duel OptD sumo -cage.blocks=false").stream().anyMatch(l -> l.contains("not a rule of sumo")),
                    "a rule the mode does not carry");
            assertTrue(chat(a, "duel OptD sumo -flow.time.limit=soon").stream().anyMatch(l -> l.contains("flow.time.limit")),
                    "a value the rule cannot parse");
            MinecraftServer.getCommandManager().execute(b.player, "duel OptC");
            for (int i = 0; i < 5; i++) MinecraftServer.getSchedulerManager().processTick();
            assertNull(Game.of(a.player), "none of those became a request to accept");
        } finally {
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void theTailCompletes() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(508.5, 65, 508.5), "OptE");
        try {
            assertEquals(List.of("bridge"), complete(a, "/duel OptE br"), "the mode, read at the request");
            assertTrue(complete(a, "/duel OptE bridge -fl").contains("-flow.time.limit"), "a flag key from the mode's rules");
            assertEquals(List.of("-look=scrims"), complete(a, "/duel OptE bridge -look=sc"));
            assertEquals(List.of("-cage.blocks=true", "-cage.blocks=false"), complete(a, "/duel OptE bridge -cage.blocks="),
                    "a boolean rule's values");
            assertFalse(complete(a, "/duel OptE bridge ").isEmpty(), "the pool's map names, bare");
        } finally {
            a.player.remove();
        }
    }
}
