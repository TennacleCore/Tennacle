package tennacle.games.bedwars;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.Component;
import net.minestom.server.network.packet.server.play.PlayerInfoRemovePacket;
import net.minestom.server.entity.PlayerSkin;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.HiddenNames;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A stranger reads as a random string in the tab and the join line until the game opens (captured, public pregame). */
class HiddenNamesTest extends HeadlessServerTest {

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
    void strangersReadAsAliases() {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = Matches.launch(solo, Matches.platformFor(null), BedWarsRoundTest.fixture());
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "HideB");
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "HideA");
        a.player.setSkin(new PlayerSkin("textures", "signature"));
        try {
            b.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(b.player)));
            await("b seated", () -> Game.of(b.player) == game);
            b.sent.clear();
            a.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player)));
            await("a seated", () -> Game.of(a.player) == game);

            String alias = HiddenNames.aliasFor(game, b.player, a.player);
            assertNotNull(alias, "a stranger");
            assertNotEquals("HideA", alias);
            assertNull(HiddenNames.aliasFor(game, a.player, a.player), "you read as yourself");
            assertEquals(alias, lastListed(b, a.player.getUuid()), "listed under the alias");
            assertTrue(lastEntry(b, a.player.getUuid()).properties().isEmpty(), "a default skin");
            assertTrue(b.sent(PlayerInfoRemovePacket.class).stream().anyMatch(r -> r.uuids().contains(a.player.getUuid())),
                    "the row goes first: a modern client keeps its first add");
            Component own = a.sent(SystemChatPacket.class).stream().map(SystemChatPacket::message)
                    .filter(m -> PlainTextComponentSerializer.plainText().serialize(m).startsWith("HideA has joined")).findFirst().orElseThrow();
            assertEquals(NamedTextColor.GRAY, own.children().getFirst().color(), "your own name: rankless gray");
            assertEquals(NamedTextColor.YELLOW, own.children().get(1).color(), "the stranger line's colors");
            List<String> chat = chat(b);
            assertTrue(chat.contains(alias + " has joined (2/" + Match.seats(game) + ")!"), chat.toString());
            assertTrue(chat.stream().noneMatch(l -> l.contains("HideA")), chat.toString());

            b.sent.clear();
            BedWarsRoundTest.live(game);
            assertNull(HiddenNames.aliasFor(game, b.player, a.player), "the real names take over");
            assertEquals("HideA", lastListed(b, a.player.getUuid()), "re-listed as themselves");
            assertTrue(!lastEntry(b, a.player.getUuid()).properties().isEmpty(), "and in their own skin");
        } finally {
            tennacle.games.watch.Replays.discard(game);
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }

    private static PlayerInfoUpdatePacket.Entry lastEntry(FakePlayer viewer, UUID subject) {
        PlayerInfoUpdatePacket.Entry last = null;
        for (PlayerInfoUpdatePacket packet : viewer.sent(PlayerInfoUpdatePacket.class)) {
            if (!packet.actions().contains(PlayerInfoUpdatePacket.Action.ADD_PLAYER)) continue;
            for (PlayerInfoUpdatePacket.Entry entry : packet.entries()) if (entry.uuid().equals(subject)) last = entry;
        }
        assertNotNull(last, "a row for the subject");
        return last;
    }

    // the name the viewer's last ADD_PLAYER row for the subject carried
    private static String lastListed(FakePlayer viewer, UUID subject) {
        String last = null;
        for (PlayerInfoUpdatePacket packet : viewer.sent(PlayerInfoUpdatePacket.class)) {
            if (!packet.actions().contains(PlayerInfoUpdatePacket.Action.ADD_PLAYER)) continue;
            for (PlayerInfoUpdatePacket.Entry entry : packet.entries()) if (entry.uuid().equals(subject)) last = entry.username();
        }
        return last;
    }

    private static List<String> chat(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }
}
