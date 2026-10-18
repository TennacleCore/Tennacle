package tennacle.social.friends;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.Seen;
import tennacle.games.TestWiring;
import tennacle.social.Audience;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FriendsTest extends HeadlessServerTest {

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
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }

    private static boolean said(List<String> lines, String text) {
        return lines.stream().anyMatch(l -> l.contains(text));
    }

    private static void befriend(FakePlayer a, FakePlayer b) throws Exception {
        Friends.befriend(a.player.getUuid(), a.player.getUsername(), b.player.getUuid(), b.player.getUsername(), 1)
                .get(5, TimeUnit.SECONDS);
    }

    @Test
    void requestAcceptRemove() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(840.5, 65, 840.5), "FrA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(842.5, 65, 842.5), "FrB");
        try {
            b.sent.clear();
            List<String> sent = chat(a, "f FrB");
            assertTrue(said(sent, "You sent a friend request to FrB! They have 5 minutes to accept it!"), sent.toString());
            assertTrue(said(plain(b), "Friend request from FrA"), plain(b).toString());
            String json = b.sent(SystemChatPacket.class).stream()
                    .map(c -> GsonComponentSerializer.gson().serialize(c.message()))
                    .filter(l -> l.contains("[ACCEPT]")).findFirst().orElseThrow();
            assertTrue(json.contains("/friend accept FrA") && json.contains("/friend deny FrA"), json);

            assertTrue(said(chat(a, "f FrB"), "Please wait before sending this person another request!"));

            a.sent.clear();
            assertTrue(said(chat(b, "friend accept FrA"), "You are now friends with FrA"));
            assertTrue(said(plain(a), "You are now friends with FrB"), plain(a).toString());
            assertTrue(Friends.are(a.player.getUuid(), b.player.getUuid()));
            assertTrue(said(chat(a, "f FrB"), "You're already friends with FrB!"));

            List<String> list = chat(a, "fl");
            assertTrue(said(list, "Friends (Page 1 of 1)") && said(list, "FrB is online"), list.toString());

            b.sent.clear();
            assertTrue(said(chat(a, "friend remove FrB"), "You removed FrB from your friends list!"));
            assertTrue(said(plain(b), "FrA removed you from their friends list!"), plain(b).toString());
            assertFalse(Friends.are(a.player.getUuid(), b.player.getUuid()));
            assertTrue(said(chat(b, "f accept FrA"), "That person hasn't invited you to be friends! Try /friend FrA"));
        } finally {
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void anOfflineRequestWaits() throws Exception {
        FakePlayer a = FakePlayer.connect(instance, new Pos(850.5, 65, 850.5), "FrC");
        FakePlayer b = FakePlayer.connect(instance, new Pos(852.5, 65, 852.5), "FrD");
        UUID away = UUID.randomUUID();
        Seen.directory().seen(away, "Ghosty", 1).get(5, TimeUnit.SECONDS);
        try {
            assertTrue(said(chat(a, "f nobodyhere"), "No player found with name nobodyhere!"));
            assertTrue(said(chat(a, "f ghosty"), "You sent an offline friend request to Ghosty! They will have 5 minutes to accept once they come online!"));
            assertEquals(0, Requests.waiting(away).get(5, TimeUnit.SECONDS).getFirst().started(), "the clock waits for them");

            Requests.send(a.player.getUuid(), "FrC", b.player.getUuid(), false).get(5, TimeUnit.SECONDS);
            b.sent.clear();
            Requests.arrived(b.player);
            assertTrue(said(plain(b), "You have 1 pending friend requests."), plain(b).toString());
            assertTrue(Requests.waiting(b.player.getUuid()).get(5, TimeUnit.SECONDS).getFirst().started() > 0, "started at login");
            List<String> page = chat(b, "f requests");
            assertTrue(said(page, "--- Friend Requests (Page 1 of 1) ---") && said(page, "From FrC"), page.toString());
            assertTrue(said(chat(b, "f deny FrC"), "Declined FrC's friend request!"));
        } finally {
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void pagesPadAndClamp() throws Exception {
        FakePlayer a = FakePlayer.connect(instance, new Pos(860.5, 65, 860.5), "FrE");
        try {
            List<Friends.Friend> many = new ArrayList<>();
            for (int i = 0; i < 10; i++) many.add(new Friends.Friend(UUID.randomUUID(), "Pal" + i, 1, i == 9));
            Friends.LIST.update(a.player.getUuid(), list -> many).get(5, TimeUnit.SECONDS);

            List<String> first = chat(a, "fl");
            assertEquals(1, first.size(), "one message");
            String[] rows = first.getFirst().split("\n");
            assertEquals(11, rows.length, "rule, header, eight rows, rule");
            assertTrue(rows[1].contains("Friends (Page 1 of 2)"));
            assertTrue(rows[2].startsWith("Pal9 "), "the best friend leads the offline ones: " + rows[2]);

            String[] last = chat(a, "fl 7").getFirst().split("\n", -1);
            assertTrue(last[1].contains("Friends (Page 2 of 2)"), "a page past the end is the last");
            assertEquals("", last[5], "a short page is padded");
            assertTrue(chat(a, "f list best").getFirst().contains("Best Friends (Page 1 of 1)"));
        } finally {
            a.player.remove();
        }
    }

    @Test
    void noticesFollowTheSetting() throws Exception {
        FakePlayer a = FakePlayer.connect(instance, new Pos(870.5, 65, 870.5), "FrF");
        FakePlayer b = FakePlayer.connect(instance, new Pos(872.5, 65, 872.5), "FrG");
        try {
            befriend(a, b);
            a.sent.clear();
            Friends.announce(b.player, "left.");
            assertTrue(said(plain(a), "Friend » FrG left."), plain(a).toString());

            assertTrue(said(chat(a, "f notifications"), "Enabled best friend join/leave notifications!"));
            a.sent.clear();
            Friends.announce(b.player, "joined.");
            assertTrue(plain(a).isEmpty(), "best friends only");
            assertTrue(said(chat(a, "f best FrG"), "FrG is now a best friend!"));
            a.sent.clear();
            Friends.announce(b.player, "joined.");
            assertTrue(said(plain(a), "Friend » FrG joined."));

            assertTrue(said(chat(a, "f notifications"), "Please wait before doing that again!"), "three seconds between toggles");
            assertEquals(Friends.Notices.BEST, Friends.notices(a.player));
        } finally {
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void friendsOnlyAdmitsFriends() throws Exception {
        FakePlayer a = FakePlayer.connect(instance, new Pos(880.5, 65, 880.5), "FrH");
        FakePlayer b = FakePlayer.connect(instance, new Pos(882.5, 65, 882.5), "FrI");
        try {
            assertFalse(Audience.FRIENDS.admits(a.player, b.player));
            befriend(a, b);
            assertTrue(Audience.FRIENDS.admits(a.player, b.player));
        } finally {
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void anOfflineListIsWritten() throws Exception {
        UUID away = UUID.randomUUID();
        Friends.LIST.update(away, list -> List.of(new Friends.Friend(UUID.randomUUID(), "Someone", 1, true)))
                .get(5, TimeUnit.SECONDS);
        assertEquals(1, Friends.of(away).get(5, TimeUnit.SECONDS).size());
        assertNull(Friends.LIST.of(away), "an offline player isn't cached");
    }

    @Test
    void spanReadsLikeHypixel() {
        long second = 1000, minute = 60 * second, hour = 60 * minute, day = 24 * hour;
        assertEquals("a year and 4 months", FriendList.span(365 * day + 4 * 30 * day + day));
        assertEquals("15 days", FriendList.span(15 * day));
        assertEquals("32 minutes and 42 seconds", FriendList.span(32 * minute + 42 * second));
        assertEquals("2 days and 15 hours", FriendList.span(2 * day + 15 * hour));
        assertEquals("3 years and a month", FriendList.span(3 * 365 * day + 30 * day));
        assertEquals("an hour", FriendList.span(hour));
    }
}
