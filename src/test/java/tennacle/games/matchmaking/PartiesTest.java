package tennacle.games.matchmaking;

import tennacle.games.TestWiring;
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

/** The party round trip in hypixel's shape: an invite that runs on click, the lineup, the channel, the leave, the end. */
class PartiesTest extends HeadlessServerTest {

    private static final String DISBANDED = "The party was disbanded because all invites expired and the party was empty.";

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
            assertTrue(said(plain(a), DISBANDED), "alone with nothing standing: " + plain(a));
            assertEquals(List.of(b.player), Parties.of(b.player));
            assertEquals(List.of(a.player), Parties.of(a.player));
        } finally {
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void anInviteRunsOut() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(812.5, 65, 812.5), "PartyG");
        FakePlayer b = FakePlayer.connect(instance, new Pos(814.5, 65, 814.5), "PartyH");
        long standing = Parties.inviteTicks;
        Parties.inviteTicks = 2;
        try {
            chat(a, "party PartyH");
            assertTrue(said(chat(a, "pl"), "Party Size (1)"), "the invite made the party");
            assertTrue(said(chat(a, "party PartyH"), "You already invited PartyH"));
            a.sent.clear();
            b.sent.clear();
            for (int i = 0; i < 5; i++) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(5);
            }
            assertTrue(said(plain(a), "The party invite to PartyH has expired."), plain(a).toString());
            assertTrue(said(plain(a), DISBANDED), plain(a).toString());
            assertTrue(said(plain(b), "The party invite from PartyG has expired."), plain(b).toString());
            assertEquals(List.of(a.player), Parties.of(a.player));
            assertTrue(said(chat(b, "party accept"), "No party invite is waiting for you."));
        } finally {
            Parties.inviteTicks = standing;
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void aDisconnectGetsAGrace() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(808.5, 65, 808.5), "PartyE");
        FakePlayer b = FakePlayer.connect(instance, new Pos(810.5, 65, 810.5), "PartyF");
        long grace = Parties.rejoinTicks;
        Parties.rejoinTicks = 2;
        try {
            chat(a, "party invite PartyF");
            chat(b, "party accept");
            a.sent.clear();
            Parties.disconnected(b.player);
            assertTrue(said(plain(a), "PartyF has disconnected, they have 0 minutes to rejoin before they are removed from the party."), plain(a).toString());
            a.sent.clear();
            Parties.rejoined(b.player);
            assertTrue(said(plain(a), "PartyF has rejoined."), plain(a).toString());
            assertEquals(List.of(a.player, b.player), Parties.of(b.player), "still in");

            b.sent.clear();
            Parties.disconnected(a.player);
            assertTrue(said(plain(b), "The party leader, PartyE has disconnected, they have 0 minutes to rejoin before the party is disbanded."), plain(b).toString());
            assertEquals(List.of(a.player, b.player), Parties.of(b.player), "the party stands through the grace");
            b.sent.clear(); // nobody rejoins: the grace runs out
            for (int i = 0; i < 5; i++) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(5);
            }
            assertTrue(said(plain(b), "The party was disbanded because the party leader disconnected."), plain(b).toString());
            assertEquals(List.of(b.player), Parties.of(b.player));
        } finally {
            Parties.rejoinTicks = grace;
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void kickTransferDisband() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(804.5, 65, 804.5), "PartyC");
        FakePlayer b = FakePlayer.connect(instance, new Pos(806.5, 65, 806.5), "PartyD");
        FakePlayer c = FakePlayer.connect(instance, new Pos(816.5, 65, 816.5), "PartyJ");
        try {
            chat(a, "party invite PartyD");
            chat(b, "party accept");
            assertTrue(said(chat(b, "party kick PartyC"), "You are not party leader."));
            assertTrue(said(chat(a, "party transfer PartyD"), "The party was transferred to PartyD by PartyC"));
            assertTrue(Parties.leads(b.player));
            b.sent.clear();
            assertTrue(said(chat(a, "party leave"), "You left the party."));
            assertTrue(said(plain(b), "PartyC has left the party."));
            assertTrue(said(plain(b), DISBANDED), plain(b).toString());
            chat(c, "party PartyC"); // stands while a starts a party of their own
            chat(a, "party PartyD");
            chat(b, "party accept");
            assertTrue(Parties.leads(a.player) && Parties.together(a.player, b.player), "a fresh party after the old one ended");
            assertTrue(said(chat(a, "party accept"), "You're already in a party."), "an invite never pulls a member out");
        } finally {
            chat(a, "party disband");
            chat(c, "party disband");
            a.player.remove();
            b.player.remove();
            c.player.remove();
        }
    }
}
