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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /duel <player> <mode> [map] [-flag ...]}: a flag the app defines rides the request, is checked before it
 * is sent, and completes; a rule is no flag, and a mode that lists none offers none.
 */
class DuelOptionsTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        // this class's own flag over a rule, on bridge alone, next to the app's preset
        Flags.register(Flag.onOff("cage", Cages.BLOCKS));
        Flags.offer(Matches.mode("bridge"), "cage");
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

    private static Game accept(FakePlayer requester, FakePlayer target) throws InterruptedException {
        MinecraftServer.getCommandManager().execute(target.player, "duel " + requester.player.getUsername());
        long deadline = System.currentTimeMillis() + 4000;
        while (Game.of(requester.player) == null && System.currentTimeMillis() < deadline) {
            MinecraftServer.getSchedulerManager().processTick();
            Thread.sleep(10);
        }
        Game game = Game.of(requester.player);
        assertNotNull(game, "the accept launched");
        return game;
    }

    @Test
    void flagsRideTheRequest() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(500.5, 65, 500.5), "OptA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(502.5, 65, 502.5), "OptB");
        Game game = null;
        try {
            List<String> sent = chat(a, "duel OptB bridge -preset=minemen -cage=off");
            assertTrue(sent.stream().anyMatch(l -> l.contains("request sent")), "the request went out: " + sent);
            game = accept(a, b);
            assertEquals("minemen", Matches.lookNameOf(game), "the preset's look");
            assertEquals("mmc18", game.rules().resolve(Scope.GAME, Presets.KEY), "and its mechanics");
            assertFalse(game.rules().resolve(Scope.GAME, Cages.BLOCKS), "a flag over a rule lands as the rule");
            Matches.modify(game, Ruleset.named("later").rule(TimeLimit.LIMIT, 600L));
            assertEquals(600L, game.rules().resolve(Scope.GAME, TimeLimit.LIMIT), "/rules still layers over it");
            assertFalse(game.rules().resolve(Scope.GAME, Cages.BLOCKS), "without dropping the flags");
        } finally {
            if (game != null) game.end(Outcome.draw());
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void noPresetIsHypixelUnderScrims() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(510.5, 65, 510.5), "OptF");
        FakePlayer b = FakePlayer.connect(instance, new Pos(512.5, 65, 512.5), "OptG");
        Game game = null;
        try {
            chat(a, "duel OptG bridge");
            game = accept(a, b);
            assertEquals(Platform.DEFAULT.name(), game.state().get(Scope.GAME, Matches.PLATFORM));
            assertEquals("hypixel", game.rules().resolve(Scope.GAME, Presets.KEY), "hypixel's rules");
            assertEquals("scrims", Matches.lookNameOf(game), "under the scrims look");
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
            assertTrue(chat(a, "duel OptD sumo -nope").stream().anyMatch(l -> l.contains("not a flag of sumo; sumo offers -preset")));
            assertTrue(chat(a, "duel OptD sumo nosuchmap").stream().anyMatch(l -> l.contains("no map named")));
            assertTrue(chat(a, "duel OptD bridge -preset=nosuch").stream().anyMatch(l -> l.contains("no preset named nosuch")));
            assertTrue(chat(a, "duel OptD bridge -flow.round.reopen=false").stream().anyMatch(l -> l.contains("not a flag of bridge")),
                    "a rule is no flag");
            assertTrue(chat(a, "duel OptD sumo -cage=off").stream().anyMatch(l -> l.contains("not a flag of sumo")),
                    "a flag another mode offers");
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
            assertEquals(Set.of("-preset", "-cage"), Set.copyOf(flags), "what bridge listed, nothing else: " + flags);
            assertEquals(Set.of("-preset=minemen", "-preset=scrims", "-preset=hypixel"), Set.copyOf(complete(a, "/duel OptE bridge -preset=")));
            assertTrue(complete(a, "/duel OptE bridge -flow.").isEmpty(), "a rule is no flag, so nothing");
            assertFalse(complete(a, "/duel OptE bridge ").isEmpty(), "the pool's map names, bare");
            assertEquals(List.of("-preset"), complete(a, "/duel OptE sumo -"), "sumo lists the preset alone");
        } finally {
            a.player.remove();
        }
    }

    @Test
    void offerNeedsTheRule() {
        Mode sumo = Matches.mode("sumo");
        assertThrows(IllegalStateException.class, () -> Flags.offer(sumo, "cage"), "no cage rule in sumo to speak of");
        assertEquals(1, Flags.of(sumo).size(), "and the refusal left the menu as it was");
    }
}
