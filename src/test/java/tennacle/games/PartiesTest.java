package tennacle.games;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The party round trip in hypixel's shape: an invite that runs on click, the lineup, the channel, the leave. */
class PartiesTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    private static List<String> chat(FakePlayer p, String command) {
        p.sent.clear();
        MinecraftServer.getCommandManager().execute(p.player, command);
        return plain(p);
    }

    private static List<String> plain(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .<String>map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }

    private static boolean said(List<String> lines, String text) {
        return lines.stream().anyMatch(l -> l.contains(text));
    }

    @Test
    void inviteJoinListChatLeave() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(800.5, 65, 800.5), "PartyA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(802.5, 65, 802.5), "PartyB");
        try {
            b.sent.clear();
            List<String> sent = chat(a, "party PartyB");
            assertTrue(said(sent, "PartyA invited PartyB to the party! They have 60 seconds to accept."), sent.toString());
            assertTrue(said(plain(b), "PartyA has invited you to join their party! You have 60 seconds to accept. Click here to join!"), plain(b).toString());
            String json = b.sent(SystemChatPacket.class).stream()
                    .map(c -> GsonComponentSerializer.gson().serialize(c.message()))
                    .filter(l -> l.contains("Click here to join")).findFirst().orElseThrow();
            assertTrue(json.contains("run_command") && json.contains("/party join PartyA"), "the click runs the join: " + json);

            a.sent.clear();
            assertTrue(said(chat(b, "party join PartyA"), "PartyB joined the party."));
            assertTrue(said(plain(a), "PartyB joined the party."), "the leader hears it too: " + plain(a));
            assertEquals(List.of(a.player, b.player), Parties.of(b.player), "one side, leader first");
            assertFalse(Parties.leads(b.player));

            List<String> lineup = chat(b, "pl");
            assertTrue(said(lineup, "Party Size (2)") && said(lineup, "Party Leader: PartyA") && said(lineup, "Party Members: PartyB"), lineup.toString());

            a.sent.clear();
            chat(b, "pc hello");
            assertTrue(said(plain(a), "P» PartyB: hello"), "party chat reaches the leader: " + plain(a));

            assertTrue(said(chat(b, "chat party"), "You are now in the party channel."));
            assertEquals(List.of(a.player, b.player), Parties.chatRoom(b.player), "the channel routes to the party");
            chat(b, "chat all");
            assertEquals(null, Parties.chatRoom(b.player));

            a.sent.clear();
            assertTrue(said(chat(b, "party leave"), "You left the party."));
            assertTrue(said(plain(a), "PartyB has left the party."), plain(a).toString());
            assertEquals(List.of(b.player), Parties.of(b.player));
        } finally {
            chat(a, "party disband");
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void kickTransferDisband() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(804.5, 65, 804.5), "PartyC");
        FakePlayer b = FakePlayer.connect(instance, new Pos(806.5, 65, 806.5), "PartyD");
        try {
            chat(a, "party invite PartyD");
            chat(b, "party accept");
            assertTrue(said(chat(b, "party kick PartyC"), "Only the party leader can do that."));
            assertTrue(said(chat(a, "party transfer PartyD"), "The party was transferred to PartyD by PartyC"));
            assertTrue(Parties.leads(b.player));
            b.sent.clear();
            assertTrue(said(chat(a, "party leave"), "You left the party."));
            assertTrue(said(plain(b), "PartyC has left the party."));
            chat(a, "party PartyD");
            assertTrue(said(chat(b, "party accept"), "No party invite is waiting for you."), "a leader cannot be invited into their own party");
        } finally {
            chat(b, "party disband");
            a.player.remove();
            b.player.remove();
        }
    }
}
