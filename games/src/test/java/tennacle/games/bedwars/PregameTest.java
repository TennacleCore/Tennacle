package tennacle.games.bedwars;

import java.util.concurrent.TimeUnit;
import java.time.LocalDate;
import java.nio.file.Path;
import java.nio.file.Files;
import org.junit.jupiter.api.io.TempDir;
import tennacle.storage.MatchLog;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.network.packet.server.play.DisplayScoreboardPacket;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import java.util.List;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.coordinate.Point;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Captured (public pregame, 2026-09-27): no team shows anywhere until the start, where a side lands together. */
class PregameTest extends HeadlessServerTest {

    private static final Pos ROOM = new Pos(0.5, 100, 0.5);
    private static final int BELOW_NAME = 2;

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path replays;

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        tennacle.glue.ReplayBridge.install();
        tennacle.games.watch.Replays.install(replays, new ShardManager());
    }

    @Test
    void teamsWaitForTheStart() {
        Mode doubles = Matches.mode("bedwars-doubles");
        Game game = Matches.launch(doubles, Matches.platformFor(null), BedWarsRoundTest.fixture());
        game.state().set(Scope.GAME, Countdown.START_AT, 99);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "PreA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "PreB");
        FakePlayer c = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "PreC");
        List<FakePlayer> all = List.of(a, b, c);
        try {
            for (FakePlayer p : all) p.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player, b.player)));
            assertTrue(Matches.enter(game, List.of(c.player)));
            await("all seated", () -> all.stream().allMatch(p -> Game.of(p.player) == game));
            for (FakePlayer p : all) assertNull(p.player.getDisplayName(), "no team color in the room");
            assertTrue(a.sent(DisplayScoreboardPacket.class).stream().noneMatch(d -> d.position() == BELOW_NAME),
                    "no health under a name in the room");

            game.state().set(Scope.GAME, Countdown.START_AT, 3);
            BedWarsRoundTest.live(game);
            assertEquals(Match.teamOf(game, a.player), Match.teamOf(game, b.player), "a side lands together");
            List<String> order = BedWars.rushOrder(game, game.roster().teams().stream().map(t -> t.id()).toList());
            assertEquals(order.subList(0, 2), List.of(Match.teamOf(game, a.player), Match.teamOf(game, c.player)),
                    "first rushes fill first");
            assertTrue(a.sent(DisplayScoreboardPacket.class).stream().anyMatch(d -> d.position() == BELOW_NAME),
                    "health under names from the start");
            for (FakePlayer p : all) {
                assertNotNull(p.player.getDisplayName(), "the team's color from the start");
                assertEquals(game.roster().team(Match.teamOf(game, p.player)).color(), p.player.getDisplayName().color());
            }
        } finally {
            tennacle.games.watch.Replays.discard(game);
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            for (FakePlayer p : all) p.player.remove();
        }
    }

    /** Captured: Hypixel sends the start block a line a message; the user, 2026-09-29: sent whole, it wrapped. */
    /** The user, 2026-10-18: first rushes fill together, whichever base is nearest; a near tie keeps team order. */
    @Test
    void rushesFillInPairs() {
        List<String> eight = List.of("red", "blue", "green", "yellow", "aqua", "white", "pink", "gray");
        // Airshow's beds: Red's nearest is Gray (54 against 80)
        Map<String, Point> airshow = Map.of("red", new Vec(-40, 63, -78), "blue", new Vec(40, 67, -78),
                "green", new Vec(78, 67, -40), "yellow", new Vec(78, 67, 40), "aqua", new Vec(40, 67, 78),
                "white", new Vec(-40, 67, 78), "pink", new Vec(-78, 67, 40), "gray", new Vec(-78, 67, -40));
        assertEquals(List.of("red", "gray", "blue", "green", "yellow", "aqua", "white", "pink"), BedWars.rushOrder(eight, airshow::get));
        // bases paired along each side: Red's nearest is Blue
        Map<String, Point> sides = Map.of("red", new Vec(-30, 64, -90), "blue", new Vec(30, 64, -90),
                "green", new Vec(90, 64, -30), "yellow", new Vec(90, 64, 30), "aqua", new Vec(30, 64, 90),
                "white", new Vec(-30, 64, 90), "pink", new Vec(-90, 64, 30), "gray", new Vec(-90, 64, -30));
        assertEquals(eight, BedWars.rushOrder(eight, sides::get));
        Map<String, Point> square = Map.of("red", new Vec(0, 64, -80), "blue", new Vec(80, 64, 0),
                "green", new Vec(0, 64, 80), "yellow", new Vec(-80, 64, 0));
        List<String> four = List.of("red", "blue", "green", "yellow");
        assertEquals(four, BedWars.rushOrder(four, square::get), "a square has no first rush");
    }

    @Test
    void theStartGoesByLine() {
        Game game = Matches.launch(Matches.mode("bedwars-solo"), Matches.platformFor(null), BedWarsRoundTest.fixture());
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "PreLineA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "PreLineB");
        List<FakePlayer> all = List.of(a, b);
        try {
            for (FakePlayer p : all) p.player.setInstance(game.instance(), ROOM).join();
            for (FakePlayer p : all) assertTrue(Matches.enter(game, List.of(p.player)));
            await("all seated", () -> all.stream().allMatch(p -> Game.of(p.player) == game));
            BedWarsRoundTest.live(game);
            List<String> said = a.sent(SystemChatPacket.class).stream()
                    .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
            String headline = " ".repeat(34) + "Bed Wars";
            int at = said.indexOf(headline);
            assertTrue(at > 0, "the headline a message of its own: " + said);
            String rule = "\u25ac".repeat(64);
            assertEquals(List.of(rule, headline, "",
                    " ".repeat(5) + "Protect your bed and destroy the enemy beds.",
                    " ".repeat(6) + "Upgrade yourself and your team by collecting",
                    " ".repeat(4) + "Iron, Gold, Emerald and Diamond from generators",
                    " ".repeat(18) + "to access powerful upgrades.", "", rule), said.subList(at - 1, at + 8));
        } finally {
            tennacle.games.watch.Replays.discard(game);
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            for (FakePlayer p : all) p.player.remove();
        }
    }

    /** The audit, 2026-09-29: a game that fills its seats through /play never reached the match log. */
    @Test
    void theFillIsLogged(@TempDir Path dir) throws Exception {
        MatchLog.install(new tennacle.storage.FileMatchStore(dir));
        Game game = BedWarsRoundTest.byArrival(Matches.launch(Matches.mode("bedwars-solo"), Matches.platformFor(null),
                BedWarsRoundTest.fixture()));
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "LogA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "LogB");
        List<FakePlayer> all = List.of(a, b);
        try {
            for (FakePlayer p : all) p.player.setInstance(game.instance(), ROOM).join();
            for (FakePlayer p : all) assertTrue(Matches.enter(game, List.of(p.player)));
            await("both seated", () -> all.stream().allMatch(p -> Game.of(p.player) == game));
            BedWarsRoundTest.live(game);
            MatchLog.ended(game, Outcome.draw());
            assertTrue(MatchLog.flush(5, TimeUnit.SECONDS));
            String logged = Files.readString(dir.resolve(LocalDate.now() + ".jsonl"));
            assertTrue(logged.contains("\"mode\":\"bedwars-solo\""), logged);
            assertTrue(logged.contains("\"LogA\"") && logged.contains("\"LogB\""), "both sides: " + logged);
        } finally {
            tennacle.games.watch.Replays.discard(game);
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            for (FakePlayer p : all) p.player.remove();
        }
    }
}
