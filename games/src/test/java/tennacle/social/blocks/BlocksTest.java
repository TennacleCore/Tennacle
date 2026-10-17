package tennacle.social.blocks;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.TestWiring;
import tennacle.games.matchmaking.Duels;
import tennacle.social.friends.Friends;
import tennacle.social.party.Parties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlocksTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    private static List<String> chat(FakePlayer p, String command) {
        p.sent.clear();
        MinecraftServer.getCommandManager().execute(p.player, command);
        return p.sent(SystemChatPacket.class).stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }

    private static boolean said(List<String> lines, String text) {
        return lines.stream().anyMatch(l -> l.contains(text));
    }

    @Test
    void aBlockCutsBothWays() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(820.5, 65, 820.5), "BkA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(822.5, 65, 822.5), "BkB");
        try {
            chat(a, "f BkB");
            chat(b, "f accept BkA");
            assertTrue(Friends.are(a.player.getUuid(), b.player.getUuid()));

            assertTrue(said(chat(a, "block BkB"), "Blocked BkB."));
            assertFalse(Friends.are(a.player.getUuid(), b.player.getUuid()), "a block ends the friendship");
            assertFalse(Parties.INVITES.admits(b.player, a.player));
            assertFalse(Duels.REQUESTS.admits(a.player, b.player));

            assertTrue(said(chat(b, "party BkA"), "You cannot invite that player since they have blocked you."));
            assertTrue(said(chat(a, "party BkB"), "You blocked BkB. Use /block remove BkB first."));
            assertTrue(said(chat(b, "msg BkA hi"), "That player blocked you!"));
            assertTrue(said(chat(a, "msg BkB hi"), "You blocked BkB. Use /block remove BkB first."));
            assertTrue(said(chat(b, "f BkA"), "You cannot add that player since they have blocked you."));
            assertTrue(said(chat(a, "ignore add BkB"), "You've already blocked BkB!"));
            assertTrue(said(chat(a, "block list"), "BkB"));
            assertTrue(said(chat(a, "block BkA"), "You cannot block yourself."));

            assertTrue(said(chat(a, "block remove BkB"), "Unblocked BkB."));
            assertTrue(Parties.INVITES.admits(b.player, a.player));
            assertTrue(said(chat(a, "block remove BkB"), "BkB isn't blocked!"));
        } finally {
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void aRequestOffersTheBlock() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(830.5, 65, 830.5), "BkC");
        FakePlayer b = FakePlayer.connect(instance, new Pos(832.5, 65, 832.5), "BkD");
        try {
            b.sent.clear();
            chat(a, "f BkD");
            String json = b.sent(SystemChatPacket.class).stream()
                    .map(c -> GsonComponentSerializer.gson().serialize(c.message()))
                    .filter(l -> l.contains("[BLOCK]")).findFirst().orElseThrow();
            assertTrue(json.contains("/block add BkC"), json);
            assertTrue(said(chat(b, "block BkC"), "Blocked BkC."));
            assertTrue(said(chat(b, "f accept BkC"), "That person hasn't invited you to be friends!"), "the block drops the request");
        } finally {
            a.player.remove();
            b.player.remove();
        }
    }
}
