package tennacle.games;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A request reads scrims' way on both ends: an [Accept] that runs the accept, and a flag line when flags rode along. */
class DuelRequestTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    private static List<String> plain(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .<String>map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }

    private static List<String> json(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .map(c -> GsonComponentSerializer.gson().serialize(c.message())).toList();
    }

    private static Game await(FakePlayer p) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 4000;
        while (Game.of(p.player) == null && System.currentTimeMillis() < deadline) {
            MinecraftServer.getSchedulerManager().processTick();
            Thread.sleep(10);
        }
        return Game.of(p.player);
    }

    @Test
    void theAcceptIsAClick() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(600.5, 65, 600.5), "ReqA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(602.5, 65, 602.5), "ReqB");
        Game game = null;
        try {
            a.sent.clear();
            b.sent.clear();
            MinecraftServer.getCommandManager().execute(a.player, "duel ReqB bridge -preset=minemen");
            assertTrue(plain(a).stream().anyMatch(l -> l.contains("Sent a Bridge duel request to ReqB")), "the sender's line: " + plain(a));
            assertTrue(plain(b).stream().anyMatch(l -> l.contains("Bridge duel request from ReqA")), "the target's line: " + plain(b));
            assertTrue(json(b).stream().anyMatch(l -> l.contains("[Accept]") && l.contains("/duel accept ReqA")),
                    "an [Accept] that runs the accept: " + json(b));
            for (FakePlayer p : List.of(a, b)) {
                assertTrue(plain(p).stream().anyMatch(l -> l.contains("Duel Flags: -preset=minemen")), "the flag line: " + plain(p));
                assertTrue(json(p).stream().anyMatch(l -> l.contains("copied version")), "explained on the hover");
            }
            MinecraftServer.getCommandManager().execute(b.player, "duel accept ReqA");
            game = await(a);
            assertNotNull(game, "the click's command launched");
            assertEquals("minemen", Matches.lookNameOf(game), "with the requester's flags");
        } finally {
            if (game != null) game.end(Outcome.draw());
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void noModeNoMenuYet() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(604.5, 65, 604.5), "ReqC");
        FakePlayer b = FakePlayer.connect(instance, new Pos(606.5, 65, 606.5), "ReqD");
        try {
            a.sent.clear();
            MinecraftServer.getCommandManager().execute(a.player, "duel ReqD");
            assertTrue(plain(a).stream().anyMatch(l -> l.contains("menu is not made yet")), "no default mode: " + plain(a));
            b.sent.clear();
            MinecraftServer.getCommandManager().execute(b.player, "duel accept ReqC");
            assertTrue(plain(b).stream().anyMatch(l -> l.contains("no duel request from ReqC")), "nothing was requested: " + plain(b));
            for (int i = 0; i < 5; i++) MinecraftServer.getSchedulerManager().processTick();
            assertNull(Game.of(a.player));
        } finally {
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void noFlagsNoFlagLine() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(608.5, 65, 608.5), "ReqE");
        FakePlayer b = FakePlayer.connect(instance, new Pos(610.5, 65, 610.5), "ReqF");
        try {
            b.sent.clear();
            MinecraftServer.getCommandManager().execute(a.player, "duel ReqF sumo");
            assertTrue(plain(b).stream().anyMatch(l -> l.contains("Sumo duel request from ReqE")), plain(b).toString());
            assertFalse(plain(b).stream().anyMatch(l -> l.contains("Duel Flags")), "no flags, no line: " + plain(b));
        } finally {
            a.player.remove();
            b.player.remove();
        }
    }
}
