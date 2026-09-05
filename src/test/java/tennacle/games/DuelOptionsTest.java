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
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /duel <player> <mode> [map] [-flag ...]}: a flag the app defines rides the request, is checked before it
 * is sent, and completes; a mode that lists none offers none.
 */
class DuelOptionsTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        // this class's own flags, on bridge alone: a rule by another name, a rule spoken as on/off, and no rule at all
        Flags.register(Flag.rule("clock", TimeLimit.LIMIT, value -> Long.parseLong(value) * 1200, List.of()));
        Flags.register(Flag.onOff("cage", Cages.BLOCKS));
        Flags.register(new Flag() {
            @Override public @NotNull String name() { return "theme"; }
            @Override public @NotNull Collection<String> values(@NotNull Mode mode) { return Matches.lookNames(); }
            @Override public void apply(@NotNull Mode mode, @NotNull String value, @NotNull Options.Builder into) { into.look(value); }
        });
        Flags.offer(Matches.mode("bridge"), "clock", "cage", "theme");
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
            List<String> sent = chat(a, "duel OptB bridge -theme=scrims -clock=1 -cage=off -flow.round.reopen=false");
            assertTrue(sent.stream().anyMatch(l -> l.contains("request sent")), "the request went out: " + sent);
            MinecraftServer.getCommandManager().execute(b.player, "duel OptA");
            game = await(a);
            assertNotNull(game, "the accept launched");
            assertEquals("scrims", Matches.lookNameOf(game), "the flag's look, not the platform's");
            assertEquals(1200L, game.rules().resolve(Scope.GAME, TimeLimit.LIMIT), "-clock lands as the rule it names");
            assertFalse(game.rules().resolve(Scope.GAME, Cages.BLOCKS), "-cage speaks on and off");
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
            assertTrue(chat(a, "duel OptD sumo -cage=off").stream().anyMatch(l -> l.contains("not a flag of sumo; sumo offers no flags")),
                    "a flag another mode offers");
            assertTrue(chat(a, "duel OptD bridge -clock=soon").stream().anyMatch(l -> l.contains("-clock=soon")),
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
            assertEquals(Set.of("-clock", "-cage", "-theme"), Set.copyOf(flags), "what bridge listed, nothing else: " + flags);
            assertFalse(flags.contains("-flow.time.limit"), "rule ids wait for a dot");
            assertTrue(complete(a, "/duel OptE bridge -flow.").contains("-flow.time.limit"), "and then they come");
            assertEquals(List.of("-cage=on", "-cage=off"), complete(a, "/duel OptE bridge -cage="));
            assertEquals(List.of("-theme=scrims"), complete(a, "/duel OptE bridge -theme=sc"));
            assertFalse(complete(a, "/duel OptE bridge ").isEmpty(), "the pool's map names, bare");
            assertTrue(complete(a, "/duel OptE sumo -").isEmpty(), "sumo listed nothing, so nothing");
        } finally {
            a.player.remove();
        }
    }

    @Test
    void offerNeedsTheRule() {
        Mode sumo = Matches.mode("sumo");
        assertThrows(IllegalStateException.class, () -> Flags.offer(sumo, "cage"), "no cage rule in sumo to speak of");
        assertTrue(Flags.of(sumo).isEmpty(), "and the refusal left the menu as it was");
    }
}
