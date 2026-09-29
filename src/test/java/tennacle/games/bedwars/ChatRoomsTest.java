package tennacle.games.bedwars;

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
import tennacle.Rooms;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TeamChat;
import tennacle.games.TestWiring;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        game.state().set(Scope.GAME, Countdown.START_AT, 99); // fills, never starts
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "ChatA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "ChatB");
        FakePlayer c = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "ChatC");
        try {
            for (FakePlayer p : List.of(a, b, c)) p.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player, b.player)));
            assertTrue(Matches.enter(game, List.of(c.player)));
            await("all seated", () -> Game.of(a.player) == game && Game.of(b.player) == game && Game.of(c.player) == game);
            assertEquals(Match.teamOf(game, a.player), Match.teamOf(game, b.player), "a pair seats together");
            assertNotEquals(Match.teamOf(game, a.player), Match.teamOf(game, c.player));

            Chat.Room room = Rooms.room(a.player, "push mid");
            assertNotNull(room);
            assertTrue(room.audience().contains(a.player) && room.audience().contains(b.player), "the team hears");
            assertFalse(room.audience().contains(c.player), "the other side does not");
            assertEquals("TEAM > ChatA: push mid", plain(room.line()));

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

    private static String plain(Component line) {
        return PlainTextComponentSerializer.plainText().serialize(line);
    }

    private static List<String> chat(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream().map(c -> plain(c.message())).toList();
    }
}
