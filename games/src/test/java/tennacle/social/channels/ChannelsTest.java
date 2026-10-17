package tennacle.social.channels;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.TestWiring;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelsTest extends HeadlessServerTest {

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
    void aChannelNeedsItsGroup() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(940.5, 65, 940.5), "ChA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(942.5, 65, 942.5), "ChB");
        try {
            assertTrue(said(chat(a, "chat party"), "You're not in a party."));
            assertTrue(said(chat(a, "chat nowhere"), "Invalid channel! Valid channels: ALL, PARTY"));

            chat(a, "party ChB");
            chat(b, "party join ChA");
            assertTrue(said(chat(a, "chat p"), "You are now in the PARTY channel"));
            assertNotNull(Channels.room(a.player, "hi"));

            chat(b, "party leave"); // the last member leaving disbands it
            assertNull(Channels.room(a.player, "hi"), "no party left, so back to everyone around");
        } finally {
            a.player.remove();
            b.player.remove();
        }
    }
}
