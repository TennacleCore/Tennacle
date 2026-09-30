package tennacle.games.bedwars;

import tennacle.games.beds.Lives;
import io.github.term4.grotto.modules.outcome.LastStanding;
import java.util.Locale;
import tennacle.looks.Palette;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.canvas.chat.Chat;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.Rooms;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TeamChat;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import tennacle.games.TestWiring;

/** A team mode talks to its team in the captured shape, Solo to everyone; a shout reaches all, once a cooldown. */
class ChatRoomsTest extends HeadlessServerTest {

    private static final Pos ROOM = new Pos(0.5, 100, 0.5);

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path replays;

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        tennacle.glue.ReplayBridge.install();
        tennacle.games.watch.Replays.install(replays, new ShardManager());
    }

    @Test
    void teamRoomsAndShouts() {
        Mode doubles = Matches.mode("bedwars-doubles");
        Game game = Matches.launch(doubles, Matches.platformFor(null), BedWarsRoundTest.fixture());
        game.state().set(Scope.GAME, Countdown.START_AT, 99); // fills until the test starts it
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "ChatA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "ChatB");
        FakePlayer c = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "ChatC");
        try {
            for (FakePlayer p : List.of(a, b, c)) p.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player, b.player)));
            assertTrue(Matches.enter(game, List.of(c.player)));
            await("all seated", () -> Game.of(a.player) == game && Game.of(b.player) == game && Game.of(c.player) == game);

            Chat.Room room = Rooms.room(a.player, "hi all");
            assertNotNull(room);
            assertTrue(room.audience().contains(c.player), "no teams before the draw: the room hears");
            assertEquals("ChatA: hi all", plain(room.line()), "and no team tag");

            game.state().set(Scope.GAME, Countdown.START_AT, 3);
            BedWarsRoundTest.live(game);
            assertEquals(Match.teamOf(game, a.player), Match.teamOf(game, b.player), "a pair lands together");
            assertNotEquals(Match.teamOf(game, a.player), Match.teamOf(game, c.player));

            room = Rooms.room(a.player, "push mid");
            assertNotNull(room);
            assertTrue(room.audience().contains(a.player) && room.audience().contains(b.player), "the team hears");
            assertFalse(room.audience().contains(c.player), "the other side does not");
            String tag = "[" + Palette.titled(game.roster().team(Match.teamOf(game, a.player))).toUpperCase(Locale.ROOT) + "] ";
            assertEquals(tag + "ChatA: push mid", plain(room.line()), "the game's own line");

            game.state().set(Scope.GAME, TeamChat.TEAM, false); // Solo's rule
            room = Rooms.room(a.player, "gg");
            assertNotNull(room);
            assertTrue(room.audience().contains(c.player), "everyone hears");
            assertTrue(plain(room.line()).endsWith("] ChatA: gg"), plain(room.line()));
            game.state().set(Scope.GAME, TeamChat.TEAM, true);

            a.sent.clear();
            c.sent.clear();
            TeamChat.shout(a.player, "rush red");
            assertTrue(chat(c).stream().anyMatch(l -> l.startsWith("[SHOUT] ") && l.endsWith("ChatA: rush red")), chat(c).toString());
            c.sent.clear();
            TeamChat.shout(a.player, "again");
            assertTrue(chat(c).isEmpty(), "the cooldown holds: " + chat(c));
            assertTrue(chat(a).stream().anyMatch(l -> l.contains("shout again")), chat(a).toString());
        } finally {
            tennacle.games.watch.Replays.discard(game);
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
            c.player.remove();
        }
    }

    @Test
    void theOutTalkApart() {
        Mode doubles = Matches.mode("bedwars-doubles");
        Game game = Matches.launch(doubles, Matches.platformFor(null), BedWarsRoundTest.fixture());
        game.state().set(Scope.GAME, Countdown.START_AT, 99);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "OutA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "OutB");
        FakePlayer c = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "OutC");
        FakePlayer d = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "OutD");
        List<FakePlayer> all = List.of(a, b, c, d);
        try {
            for (FakePlayer p : all) p.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player, b.player)));
            assertTrue(Matches.enter(game, List.of(c.player)));
            assertTrue(Matches.enter(game, List.of(d.player)));
            await("all seated", () -> all.stream().allMatch(p -> Game.of(p.player) == game));
            game.state().set(Scope.GAME, Countdown.START_AT, 4);
            BedWarsRoundTest.live(game);

            game.module(LastStanding.class).out(d.player, LastStanding.Cause.eliminated(null, "was eliminated"));
            assertNull(game.decided(), "two sides still stand");
            Chat.Room room = Rooms.room(d.player, "gg");
            assertNotNull(room);
            assertTrue(room.audience().contains(d.player));
            assertFalse(room.audience().contains(a.player) || room.audience().contains(c.player), "the living never hear the out");
            assertEquals("[SPECTATOR] OutD: gg", plain(room.line()));

            game.module(Lives.class).fell(b.player); // the bed stands: a death to come back from
            room = Rooms.room(b.player, "rush");
            assertNotNull(room);
            assertTrue(room.audience().contains(a.player) && room.audience().contains(b.player), "a respawning death keeps its team");
            assertFalse(room.audience().contains(c.player));

            a.sent.clear();
            d.sent.clear();
            TeamChat.shout(d.player, "hey");
            assertTrue(chat(a).stream().noneMatch(l -> l.contains("hey")), "nor a shout from the out");
            assertTrue(chat(d).stream().anyMatch(l -> l.contains("can't shout")), chat(d).toString());
        } finally {
            tennacle.games.watch.Replays.discard(game);
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            for (FakePlayer p : all) p.player.remove();
        }
    }

    private static String plain(Component line) {
        return PlainTextComponentSerializer.plainText().serialize(line);
    }

    private static List<String> chat(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream().map(c -> plain(c.message())).toList();
    }
}
