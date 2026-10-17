package tennacle.games.matchmaking;

import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Flags;
import tennacle.games.TestWiring;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tennacle.social.party.Parties;
/** A request reads scrims' way on both ends: an [Accept] that runs the accept, and a flag line when flags rode along. */
class DuelRequestTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    private static List<String> plain(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .<String>map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }

    private static List<String> json(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .map(c -> GsonComponentSerializer.gson().serialize(c.message())).toList();
    }

    private static Game await(FakePlayer p) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 4000;
        while (Game.of(p.player) == null && System.currentTimeMillis() < deadline) {
            MinecraftServer.getSchedulerManager().processTick();
            Thread.sleep(10);
        }
        return Game.of(p.player);
    }

    /** A mode that fills a waiting room is /play's: /duel turns it away. */
    @Test
    void aFillModeIsPlays() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(600.5, 65, 600.5), "FillA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(602.5, 65, 602.5), "FillB");
        try {
            a.sent.clear();
            b.sent.clear();
            MinecraftServer.getCommandManager().execute(a.player, "duel FillB bedwars-solo");
            assertEquals(1, plain(a).stream().filter(l -> l.contains("fills up through /play bedwars_eight_one")).count(),
                    "turned away: " + plain(a));
            assertTrue(plain(b).stream().noneMatch(l -> l.contains("FillA")), "no request reached them: " + plain(b));
            assertFalse(Matches.launchedFull().contains("bedwars-solo"));
            assertTrue(Matches.launchedFull().contains("bridge"));
        } finally {
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void theAcceptIsAClick() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(600.5, 65, 600.5), "ReqA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(602.5, 65, 602.5), "ReqB");
        Game game = null;
        try {
            a.sent.clear();
            b.sent.clear();
            MinecraftServer.getCommandManager().execute(a.player, "duel ReqB bridge -preset=minemen");
            assertTrue(plain(a).stream().anyMatch(l -> l.contains("Sent a Bridge duel request to ReqB on Random")), "the sender's line names the pool: " + plain(a));
            assertTrue(plain(b).stream().anyMatch(l -> l.contains("Bridge duel request from ReqA on Random")), "the target's line too: " + plain(b));
            assertTrue(json(b).stream().anyMatch(l -> l.contains("[Accept]") && l.contains("/duel accept ReqA")),
                    "an [Accept] that runs the accept: " + json(b));
            for (FakePlayer p : List.of(a, b)) {
                assertTrue(plain(p).stream().anyMatch(l -> l.contains("Duel Flags: -preset=minemen")), "the flag line: " + plain(p));
                assertTrue(json(p).stream().anyMatch(l -> l.contains("rules and look")), "explained on the hover");
            }
            MinecraftServer.getCommandManager().execute(b.player, "duel accept ReqA");
            game = await(a);
            assertNotNull(game, "the click's command launched");
            assertEquals("minemen", Matches.lookNameOf(game), "with the requester's flags");
        } finally {
            if (game != null) game.end(Outcome.draw());
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void duelingBackIsARequest() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(612.5, 65, 612.5), "ReqG");
        FakePlayer b = FakePlayer.connect(instance, new Pos(614.5, 65, 614.5), "ReqH");
        try {
            MinecraftServer.getCommandManager().execute(a.player, "duel ReqH sumo");
            a.sent.clear();
            MinecraftServer.getCommandManager().execute(b.player, "duel ReqG sumo");
            for (int i = 0; i < 5; i++) MinecraftServer.getSchedulerManager().processTick();
            assertNull(Game.of(a.player), "a request back is not an accept");
            assertTrue(plain(a).stream().anyMatch(l -> l.contains("Sumo duel request from ReqH")), "it is another request: " + plain(a));
        } finally {
            a.player.remove();
            b.player.remove();
        }
    }

    private static Game awaitAll(FakePlayer... players) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline) {
            boolean all = true;
            for (FakePlayer p : players) all &= Game.of(p.player) != null;
            if (all) break;
            MinecraftServer.getSchedulerManager().processTick();
            Thread.sleep(10);
        }
        return Game.of(players[0].player);
    }

    /** A leader's request reaches the other party whole; any of them accepts and brings the party; sizes warn. */
    @Test
    void aPartyDuelsAParty() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(620.5, 65, 620.5), "PdA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(622.5, 65, 622.5), "PdB");
        FakePlayer c = FakePlayer.connect(instance, new Pos(624.5, 65, 624.5), "PdC");
        FakePlayer d = FakePlayer.connect(instance, new Pos(626.5, 65, 626.5), "PdD");
        Game game = null;
        try {
            MinecraftServer.getCommandManager().execute(a.player, "party invite PdB");
            MinecraftServer.getCommandManager().execute(b.player, "party accept");
            MinecraftServer.getCommandManager().execute(c.player, "party invite PdD");
            MinecraftServer.getCommandManager().execute(d.player, "party accept");

            b.sent.clear();
            MinecraftServer.getCommandManager().execute(b.player, "duel PdC sumo");
            assertTrue(plain(b).stream().anyMatch(l -> l.contains("You are not party leader.")), plain(b).toString());

            for (FakePlayer p : List.of(a, b, c, d)) p.sent.clear();
            MinecraftServer.getCommandManager().execute(a.player, "duel PdD sumo"); // to a member, not the leader
            assertTrue(plain(b).stream().anyMatch(l -> l.contains("Sent a Sumo duel request to PdD")), "the sender's party reads the sent line: " + plain(b));
            assertTrue(plain(c).stream().anyMatch(l -> l.contains("Sumo duel request from PdA")), "the leader of the other party reads it: " + plain(c));
            assertTrue(plain(d).stream().anyMatch(l -> l.contains("Sumo duel request from PdA")), "and so does the one named: " + plain(d));
            assertTrue(plain(c).stream().noneMatch(l -> l.contains("WARNING")), "2v2: no warning");

            MinecraftServer.getCommandManager().execute(c.player, "duel accept PdA"); // the one not named accepts
            game = awaitAll(a, b, c, d);
            assertNotNull(game, "the whole of both parties is seated");
            assertEquals(Match.teamOf(game, a.player), Match.teamOf(game, b.player), "a party is one side");
            assertEquals(Match.teamOf(game, c.player), Match.teamOf(game, d.player));
            assertFalse(Match.teamOf(game, a.player).equals(Match.teamOf(game, c.player)));
        } finally {
            if (game != null) game.end(Outcome.draw());
            for (FakePlayer p : List.of(a, b, c, d)) p.player.remove();
        }
    }

    @Test
    void unevenSidesWarnAndMatesDuelWithin() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(630.5, 65, 630.5), "PdE");
        FakePlayer b = FakePlayer.connect(instance, new Pos(632.5, 65, 632.5), "PdF");
        FakePlayer c = FakePlayer.connect(instance, new Pos(634.5, 65, 634.5), "PdG");
        Game game = null;
        try {
            MinecraftServer.getCommandManager().execute(a.player, "party invite PdF");
            MinecraftServer.getCommandManager().execute(b.player, "party accept");
            for (FakePlayer p : List.of(a, b, c)) p.sent.clear();
            MinecraftServer.getCommandManager().execute(a.player, "duel PdG sumo");
            for (FakePlayer p : List.of(a, b, c)) {
                assertTrue(plain(p).stream().anyMatch(l -> l.contains("WARNING: Unbalanced team sizes (2v1)")), plain(p).toString());
            }

            // party mates duel each other 1v1 without leaving the party, whoever leads
            b.sent.clear();
            MinecraftServer.getCommandManager().execute(b.player, "duel PdE sumo");
            assertTrue(plain(b).stream().anyMatch(l -> l.contains("Sent a Sumo duel request to PdE")), plain(b).toString());
            MinecraftServer.getCommandManager().execute(a.player, "duel accept PdF");
            game = awaitAll(a, b);
            assertNotNull(game);
            assertFalse(Match.teamOf(game, a.player).equals(Match.teamOf(game, b.player)), "against each other");
            assertNull(Game.of(c.player), "the outsider stays out");
            assertEquals(List.of(a.player, b.player), Parties.of(b.player), "the party stands");
        } finally {
            if (game != null) game.end(Outcome.draw());
            for (FakePlayer p : List.of(a, b, c)) p.player.remove();
        }
    }

    /** Kicked, a member can no longer accept for a party they are not in; disbanded, the leader still can, alone. */
    @Test
    void theLeaderAloneAcceptsAfterTheBreakup() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(640.5, 65, 640.5), "BrkA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(642.5, 65, 642.5), "BrkB");
        FakePlayer c = FakePlayer.connect(instance, new Pos(644.5, 65, 644.5), "BrkC");
        Game game = null;
        try {
            MinecraftServer.getCommandManager().execute(a.player, "party invite BrkB");
            MinecraftServer.getCommandManager().execute(b.player, "party accept");
            MinecraftServer.getCommandManager().execute(c.player, "duel BrkB sumo");
            MinecraftServer.getCommandManager().execute(a.player, "party kick BrkB");
            b.sent.clear();
            MinecraftServer.getCommandManager().execute(b.player, "duel accept BrkC");
            assertTrue(plain(b).stream().anyMatch(l -> l.contains("No duel request from BrkC")), "kicked: not theirs to accept: " + plain(b));
            for (int i = 0; i < 5; i++) MinecraftServer.getSchedulerManager().processTick();
            assertNull(Game.of(b.player));

            MinecraftServer.getCommandManager().execute(a.player, "party disband");
            MinecraftServer.getCommandManager().execute(a.player, "duel accept BrkC");
            game = awaitAll(a, c);
            assertNotNull(game, "the leader it reached accepts, alone");
            assertNull(Game.of(b.player), "the kicked member stays out");
        } finally {
            if (game != null) game.end(Outcome.draw());
            for (FakePlayer p : List.of(a, b, c)) p.player.remove();
        }
    }

    /** A transfer keeps the request with the party: the new leader accepts it. */
    @Test
    void theNewLeaderAcceptsAfterATransfer() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(650.5, 65, 650.5), "TrfA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(652.5, 65, 652.5), "TrfB");
        FakePlayer c = FakePlayer.connect(instance, new Pos(654.5, 65, 654.5), "TrfC");
        Game game = null;
        try {
            MinecraftServer.getCommandManager().execute(a.player, "party invite TrfB");
            MinecraftServer.getCommandManager().execute(b.player, "party accept");
            MinecraftServer.getCommandManager().execute(c.player, "duel TrfA sumo");
            MinecraftServer.getCommandManager().execute(a.player, "party transfer TrfB");
            MinecraftServer.getCommandManager().execute(b.player, "duel accept TrfC");
            game = awaitAll(a, b, c);
            assertNotNull(game, "the party the request reached still stands, under its new leader");
            assertEquals(Match.teamOf(game, a.player), Match.teamOf(game, b.player), "both of them, as one side");
        } finally {
            if (game != null) game.end(Outcome.draw());
            for (FakePlayer p : List.of(a, b, c)) p.player.remove();
        }
    }

    @Test
    void noFlagsNoFlagLine() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(608.5, 65, 608.5), "ReqE");
        FakePlayer b = FakePlayer.connect(instance, new Pos(610.5, 65, 610.5), "ReqF");
        try {
            b.sent.clear();
            MinecraftServer.getCommandManager().execute(a.player, "duel ReqF sumo");
            assertTrue(plain(b).stream().anyMatch(l -> l.contains("Sumo duel request from ReqE")), plain(b).toString());
            assertFalse(plain(b).stream().anyMatch(l -> l.contains("Duel Flags")), "no flags, no line: " + plain(b));
        } finally {
            a.player.remove();
            b.player.remove();
        }
    }
}
