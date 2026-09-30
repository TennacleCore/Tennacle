package tennacle.lobby;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /duel <player>} with no game opens the lobby's picker, and requests nothing. */
class DuelPickerTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        LobbyWiring.ensure(instance);
    }

    private static List<String> plain(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .map(chat -> PlainTextComponentSerializer.plainText().serialize(chat.message())).toList();
    }

    @Test
    void noModeOpensPicker() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(604.5, 65, 604.5), "ReqC");
        FakePlayer b = FakePlayer.connect(instance, new Pos(606.5, 65, 606.5), "ReqD");
        try {
            a.sent.clear();
            MinecraftServer.getCommandManager().execute(a.player, "duel ReqD");
            assertTrue(io.github.term4.canvas.screen.Screens.screen(a.player) != null, "the duel picker opens");
            b.sent.clear();
            MinecraftServer.getCommandManager().execute(b.player, "duel accept ReqC");
            assertTrue(plain(b).stream().anyMatch(l -> l.contains("No duel request from ReqC")), "nothing was requested: " + plain(b));
            for (int i = 0; i < 5; i++) MinecraftServer.getSchedulerManager().processTick();
            assertNull(Game.of(a.player));
        } finally {
            a.player.remove();
            b.player.remove();
        }
    }
}
