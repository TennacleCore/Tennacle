package tennacle.games.matchmaking;

import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /play} by Hypixel's ids: a duel id queues, strangers fill a team together, and it works from a game. */
class PlayTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    private static void run(FakePlayer p, String command) {
        MinecraftServer.getCommandManager().execute(p.player, command);
    }

    private static Game awaitGame(FakePlayer p) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (Game.of(p.player) == null && System.currentTimeMillis() < deadline) {
            MinecraftServer.getSchedulerManager().processTick();
            Thread.sleep(10);
        }
        return Game.of(p.player);
    }

    private static List<String> plain(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .<String>map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }

    @Test
    void strangersFillDoubles() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "PlayA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(2.5, 65, 2.5), "PlayB");
        FakePlayer c = FakePlayer.connect(instance, new Pos(4.5, 65, 4.5), "PlayC");
        FakePlayer d = FakePlayer.connect(instance, new Pos(6.5, 65, 6.5), "PlayD");
        Game game = null;
        try {
            run(a, "party PlayB");
            run(b, "party join PlayA");
            run(c, "play duels_bridge_doubles");
            assertTrue(Queues.waiting(c.player), "a solo waits for a partner");
            run(a, "play duels_bridge_doubles");
            assertTrue(Queues.waiting(a.player), "a full team waits for the other");
            run(d, "play duels_bridge_doubles");
            game = awaitGame(a);
            assertNotNull(game, "the fourth seat starts it");
            assertEquals(Match.teamOf(game, a.player), Match.teamOf(game, b.player), "the party together");
            assertEquals(Match.teamOf(game, c.player), Match.teamOf(game, d.player), "the strangers together");
            assertNotEquals(Match.teamOf(game, a.player), Match.teamOf(game, c.player));
            assertFalse(Queues.waiting(c.player) || Queues.waiting(d.player));
        } finally {
            if (game != null && game.status() == Game.Status.OPEN) game.end(Outcome.draw());
            for (FakePlayer p : List.of(a, b, c, d)) {
                Queues.dequeue(p.player);
                p.player.remove();
            }
        }
    }

    @Test
    void aBigPartyIsRefused() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(10.5, 65, 0.5), "BigA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(12.5, 65, 2.5), "BigB");
        try {
            run(a, "party BigB");
            run(b, "party join BigA");
            a.sent.clear();
            run(a, "play duels_sumo_duel");
            assertFalse(Queues.waiting(a.player));
            assertTrue(plain(a).stream().anyMatch(l -> l.contains("Your party of 2 doesn't fit; Sumo takes 1 a side.")), "" + plain(a));
        } finally {
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void theLobbyStepsOut() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(20.5, 65, 0.5), "StepA");
        try {
            run(a, "play duels_sumo_duel");
            assertTrue(Queues.waiting(a.player));
            run(a, "lobby");
            assertFalse(Queues.waiting(a.player));
        } finally {
            Queues.dequeue(a.player);
            a.player.remove();
        }
    }

    @Test
    void playLeavesAGame() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(40.5, 65, 0.5), "FromA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(42.5, 65, 0.5), "FromB");
        Game game = null;
        try {
            run(a, "play duels_sumo_duel");
            run(b, "play duels_sumo_duel");
            game = awaitGame(a);
            assertNotNull(game, "the pair starts");
            a.sent.clear();
            run(a, "play duels_sumo_duel");
            assertNull(Game.of(a.player), "out of the game");
            assertTrue(Queues.waiting(a.player), "and in the line: " + plain(a));
            assertTrue(plain(a).stream().anyMatch(l -> l.startsWith("You left")), "" + plain(a));
        } finally {
            if (game != null && game.status() == Game.Status.OPEN) game.end(Outcome.draw());
            for (FakePlayer p : List.of(a, b)) {
                Queues.dequeue(p.player);
                p.player.remove();
            }
        }
    }

    @Test
    void aBusyMateAsksTwice() throws InterruptedException {
        FakePlayer lead = FakePlayer.connect(instance, new Pos(50.5, 65, 0.5), "BusyLead");
        FakePlayer mate = FakePlayer.connect(instance, new Pos(52.5, 65, 0.5), "BusyMate");
        FakePlayer foe = FakePlayer.connect(instance, new Pos(54.5, 65, 0.5), "BusyFoe");
        Game game = null;
        try {
            run(mate, "play duels_sumo_duel");
            run(foe, "play duels_sumo_duel");
            game = awaitGame(mate);
            assertNotNull(game);
            run(lead, "party BusyMate");
            run(mate, "party join BusyLead");
            lead.sent.clear();
            run(lead, "play duels_bridge_doubles");
            assertFalse(Queues.waiting(lead.player), "held up");
            assertTrue(plain(lead).stream().anyMatch(l -> l.contains("BusyMate is in a game. Type it again within 10 seconds")),
                    "" + plain(lead));
            assertSame(game, Game.of(mate.player), "the mate plays on");
            run(lead, "play duels_bridge_doubles");
            assertTrue(Queues.waiting(lead.player), "the second ask goes ahead: " + plain(lead));
            assertNull(Game.of(mate.player), "and brings the mate out");
        } finally {
            if (game != null && game.status() == Game.Status.OPEN) game.end(Outcome.draw());
            run(lead, "party disband");
            for (FakePlayer p : List.of(lead, mate, foe)) {
                Queues.dequeue(p.player);
                p.player.remove();
            }
        }
    }

    @Test
    void aMapPickWaitsForItsMatch() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(60.5, 65, 0.5), "MapA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(62.5, 65, 0.5), "MapB");
        FakePlayer c = FakePlayer.connect(instance, new Pos(64.5, 65, 0.5), "MapC");
        Mode sumo = Matches.played("duels_sumo_duel");
        Game game = null;
        try {
            String map = sumo.arena().names().iterator().next();
            run(a, "play duels_sumo_duel " + map);
            run(b, "play duels_sumo_duel");
            assertTrue(Queues.waiting(a.player) && Queues.waiting(b.player), "a pick never meets a random");
            run(c, "play duels_sumo_duel " + Matches.mapName(sumo, map).toUpperCase().replace(' ', '_'));
            game = awaitGame(a);
            assertNotNull(game, "the same pick does");
            assertSame(game, Game.of(c.player));
            assertSame(sumo.arena().named(map), game.arena(), "on that map");
            assertTrue(Queues.waiting(b.player));
        } finally {
            if (game != null && game.status() == Game.Status.OPEN) game.end(Outcome.draw());
            for (FakePlayer p : List.of(a, b, c)) {
                Queues.dequeue(p.player);
                p.player.remove();
            }
        }
    }

    @Test
    void anUnknownIdLists() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(30.5, 65, 0.5), "IdA");
        try {
            run(a, "play bridge");
            assertTrue(plain(a).stream().anyMatch(l -> l.contains("No game named bridge")
                    && l.contains("duels_bridge_doubles") && l.contains("bedwars_eight_two")), "" + plain(a));
        } finally {
            a.player.remove();
        }
    }
}
