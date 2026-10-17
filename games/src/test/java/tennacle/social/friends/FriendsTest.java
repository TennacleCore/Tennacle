package tennacle.social.friends;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.TestWiring;
import tennacle.social.Audience;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FriendsTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void friendsOnlyAdmitsFriends() throws Exception {
        FakePlayer a = FakePlayer.connect(instance, new Pos(840.5, 65, 840.5), "FriendA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(842.5, 65, 842.5), "FriendB");
        try {
            assertFalse(Audience.FRIENDS.admits(a.player, b.player));
            Friends.LIST.update(b.player.getUuid(),
                    list -> List.of(new Friends.Friend(a.player.getUuid(), "FriendA", 1, false))).get(5, TimeUnit.SECONDS);
            assertTrue(Audience.FRIENDS.admits(a.player, b.player));
            assertTrue(Friends.are(a.player.getUuid(), b.player.getUuid()), "either side's list answers");
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
        assertEquals(1, Friends.LIST.load(away).get(5, TimeUnit.SECONDS).size());
        assertEquals(null, Friends.LIST.of(away), "an offline player isn't cached");
    }
}
