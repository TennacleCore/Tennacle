package tennacle.games.bridge;

import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.combat.Respawns;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A bridge seat walked out on says so, waits, and comes back at the respawn spot behind protection. */
class RejoinTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    private static void execute(FakePlayer p, String command) {
        p.sent.clear();
        net.minestom.server.MinecraftServer.getCommandManager().execute(p.player, command);
    }

    /** A held seat holds through another game: the first game waits, and a code picks it back over the later one. */
    @Test
    void heldThroughAnotherGame() {
        Mode bridge = Matches.mode("bridge");
        Game first = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        Game second = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "HoldRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "HoldBlue");
        try {
            red.player.setInstance(first.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(first.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(first.join(red.player));
            assertTrue(first.join(blue.player));
            await("both seated", () -> Game.of(red.player) == first && Game.of(blue.player) == first);
            first.depart(red.player);

            execute(red, "spectate HoldBlue");
            assertTrue(said(red, "your own game"), "a held seat is no view: " + red.sent.size());
            assertTrue(Matches.grotto().spectating(red.player) == null);

            red.player.setInstance(second.instance(), new Pos(-4.5, 65, 0.5)).join();
            assertTrue(second.join(red.player), "free to play elsewhere");
            await("seated in the second", () -> Game.of(red.player) == second);
            assertSame(first, Matches.grotto().reclaimable(red.player), "the first still holds");
            second.depart(red.player);
            assertEquals(java.util.List.of(second, first), Matches.grotto().reclaimables(red.player), "both hold, the later first");

            execute(red, "rejoin");
            assertTrue(said(red, "/games"), "several holds: no guess, the list");
            execute(red, "games");
            assertTrue(said(red, "#" + first.code()) && said(red, "#" + second.code()), "both games, by mode and code");
            assertTrue(said(red, "with HoldBlue"), "and who is in it");

            execute(red, "rejoin " + first.code());
            BridgeRoundTest.tickUntil(first, "back in the first", () -> Game.of(red.player) == first); // a world hop binds on a tick
            assertTrue(said(red, "#" + first.code()), "named by its code");
        } finally {
            for (Game game : java.util.List.of(first, second)) if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    private static boolean said(FakePlayer p, String line) {
        return p.sent(SystemChatPacket.class).stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message()))
                .anyMatch(l -> l.contains(line));
    }

    @Test
    void leftThenRejoined() {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "RejoinRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "RejoinBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            BridgeRoundTest.tickUntil(game, "GO", () -> Match.live(game));
            String team = Bridge.teamOf(game, red.player);
            double home = game.arena().marker(Bridge.RESPAWN, team).pos().x();

            blue.sent.clear();
            game.depart(red.player);
            assertTrue(said(blue, "RejoinRed left!"), "a held seat is a walk-out, not a forfeit");
            assertSame(game, Matches.grotto().reclaimable(red.player), "and it waits");
            assertEquals(Game.Status.OPEN, game.status());

            blue.sent.clear();
            assertTrue(game.join(red.player), "the seat is re-claimed");
            await("rebound", () -> Game.of(red.player) == game);
            assertTrue(said(blue, "RejoinRed rejoined!"), "and everyone hears it");
            assertEquals(home, red.player.getPosition().x(), 1e-6, "back at the respawn spot, not the cage");
            Respawns respawns = game.module(Respawns.class);
            assertTrue(respawns != null && respawns.isProtected(red.player), "behind protection");
            game.decide(Outcome.draw(), 1);
        } finally {
            red.player.remove();
            blue.player.remove();
        }
    }
}
