package tennacle.games;

import tennacle.games.bridge.Cages;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Rounds;
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

/** {@code /duel <player> <mode> [map] [-flag ...]}: flags ride the request, are checked before it is sent, and complete. */
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
            List<String> sent = chat(a, "duel OptB bridge -look=scrims -time=1 -cages=off -flow.round.reopen=false");
            assertTrue(sent.stream().anyMatch(l -> l.contains("request sent")), "the request went out: " + sent);
            MinecraftServer.getCommandManager().execute(b.player, "duel OptA");
            game = await(a);
            assertNotNull(game, "the accept launched");
            assertEquals("scrims", Matches.lookNameOf(game), "the flag's look, not the platform's");
            assertEquals(1200L, game.rules().resolve(Scope.GAME, TimeLimit.LIMIT), "-time speaks minutes");
            assertFalse(game.rules().resolve(Scope.GAME, Cages.BLOCKS), "-cages speaks on and off");
            assertFalse(game.rules().resolve(Scope.GAME, Rounds.REOPEN), "a raw rule id still lands");
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
            assertTrue(chat(a, "duel OptD sumo -nope").stream().anyMatch(l -> l.contains("unknown flag -nope")));
            assertTrue(chat(a, "duel OptD sumo nosuchmap").stream().anyMatch(l -> l.contains("no map named")));
            assertTrue(chat(a, "duel OptD sumo -look=nosuchlook").stream().anyMatch(l -> l.contains("no look named")));
            assertTrue(chat(a, "duel OptD sumo -cages=off").stream().anyMatch(l -> l.contains("not a flag of sumo")),
                    "a flag over a rule the mode lacks");
            assertTrue(chat(a, "duel OptD sumo -goals=3").stream().anyMatch(l -> l.contains("not a flag of sumo")),
                    "a flag another mode owns");
            assertTrue(chat(a, "duel OptD sumo -time=soon").stream().anyMatch(l -> l.contains("-time=soon")),
                    "a value the flag cannot read");
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
            List<String> flags = complete(a, "/duel OptE bridge -");
            assertTrue(flags.containsAll(List.of("-cages", "-goals", "-look", "-map", "-platform", "-preset", "-time")), "bridge's flags: " + flags);
            assertFalse(flags.contains("-hits"), "not boxing's");
            assertFalse(flags.contains("-flow.time.limit"), "rule ids wait for a dot");
            assertTrue(complete(a, "/duel OptE bridge -flow.").contains("-flow.time.limit"), "and then they come");
            assertEquals(List.of("-cages=on", "-cages=off"), complete(a, "/duel OptE bridge -cages="));
            assertEquals(List.of("-look=scrims"), complete(a, "/duel OptE bridge -look=sc"));
            assertFalse(complete(a, "/duel OptE bridge ").isEmpty(), "the pool's map names, bare");
            assertTrue(complete(a, "/duel OptE sumo -").contains("-time") && !complete(a, "/duel OptE sumo -").contains("-cages"),
                    "sumo has a clock and no cage");
        } finally {
            a.player.remove();
        }
    }
}
