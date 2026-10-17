package tennacle.social.blocks;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.TestWiring;
import tennacle.games.matchmaking.Duels;
import tennacle.social.party.Parties;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlocksTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void aBlockCutsBothWays() throws Exception {
        FakePlayer a = FakePlayer.connect(instance, new Pos(820.5, 65, 820.5), "BlockA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(822.5, 65, 822.5), "BlockB");
        try {
            assertTrue(Parties.INVITES.admits(a.player, b.player));
            Blocks.LIST.update(a.player.getUuid(), list -> {
                List<Blocks.Blocked> more = new ArrayList<>(list);
                more.add(new Blocks.Blocked(b.player.getUuid(), "BlockB"));
                return more;
            }).get(5, TimeUnit.SECONDS);

            assertFalse(Parties.INVITES.admits(b.player, a.player), "the blocked can't reach in");
            assertFalse(Parties.INVITES.admits(a.player, b.player), "nor the one who blocked reach out");
            assertFalse(Duels.REQUESTS.admits(b.player, a.player));

            Blocks.LIST.update(a.player.getUuid(), list -> List.of()).get(5, TimeUnit.SECONDS);
            assertTrue(Parties.INVITES.admits(b.player, a.player));
        } finally {
            a.player.remove();
            b.player.remove();
        }
    }
}
