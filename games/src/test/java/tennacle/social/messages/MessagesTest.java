package tennacle.social.messages;

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
import tennacle.games.Rooms;
import tennacle.games.TestWiring;
import tennacle.net.Network;
import tennacle.net.Presence;
import tennacle.net.Topics;
import tennacle.social.Audience;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest extends HeadlessServerTest {

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

    // the harness has no ticker: the answers land through scheduled tasks
    private static void tickUntil(String what, java.util.function.BooleanSupplier done) {
        long until = System.currentTimeMillis() + 5_000;
        while (!done.getAsBoolean()) {
            if (System.currentTimeMillis() > until) throw new AssertionError("never: " + what);
            MinecraftServer.getSchedulerManager().processTick();
            MinecraftServer.getSchedulerManager().processTickEnd();
        }
    }

    @Test
    void aMessageCrossesServers() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(930.5, 65, 930.5), "MsFar");
        UUID far = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        Seen.directory().seen(far, "Faraway", System.currentTimeMillis()).join();
        Seen.directory().seen(gone, "Hanging", System.currentTimeMillis()).join();
        Network.presence().joined(new Presence.Here(far, "Faraway", "server-2", 1, false)).join();
        Network.presence().joined(new Presence.Here(gone, "Hanging", "server-3", 1, false)).join();
        List<Topics.Whisper> carried = new CopyOnWriteArrayList<>();
        Network.bus().subscribe(Topics.WHISPER, w -> { // server-2, answering for its player
            if (!w.to().equals(far)) return;
            carried.add(w);
            Network.bus().publish(Topics.WHISPERED, new Topics.Whispered(w.id(), "server-2", "Faraway", null));
        });
        long answer = Messages.answerMillis;
        try {
            chat(a, "msg Faraway hello there");
            tickUntil("the other server's answer", () -> said(plain(a), "To Faraway: hello there"));
            assertTrue(carried.getFirst().fromName().equals("MsFar") && carried.getFirst().text().equals("hello there"));

            Messages.answerMillis = 50;
            chat(a, "msg Hanging anyone?");
            tickUntil("the wait runs out", () -> said(plain(a), "That player is not online!"));
            assertFalse(said(plain(a), "To Hanging"));
            assertTrue(said(chat(a, "msg Nobody hi"), "Can't find a player by the name of 'Nobody'"));
        } finally {
            Messages.answerMillis = answer;
            Network.presence().left(far, "server-2").join();
            Network.presence().left(gone, "server-3").join();
            a.player.remove();
        }
    }

    @Test
    void messageAndReply() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(900.5, 65, 900.5), "MsA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(902.5, 65, 902.5), "MsB");
        FakePlayer c = FakePlayer.connect(instance, new Pos(904.5, 65, 904.5), "MsC");
        try {
            b.sent.clear();
            assertTrue(said(chat(a, "msg MsB hi there"), "To MsB: hi there"));
            assertTrue(said(plain(b), "From MsA: hi there"), plain(b).toString());
            String json = b.sent(SystemChatPacket.class).stream()
                    .map(m -> GsonComponentSerializer.gson().serialize(m.message())).findFirst().orElseThrow();
            assertTrue(json.contains("suggest_command") && json.contains("/msg MsA "), json);

            a.sent.clear();
            assertTrue(said(chat(b, "r yo"), "To MsA: yo"));
            assertTrue(said(plain(a), "From MsB: yo"));
            assertTrue(said(chat(c, "r hello"), "Nobody has messaged you in the past 5 minutes!"));
            List<String> self = chat(a, "w MsA hi");
            assertTrue(said(self, "To MsA: hi") && said(self, "From MsA: hi"), "a note to yourself: " + self);
        } finally {
            a.player.remove();
            b.player.remove();
            c.player.remove();
        }
    }

    @Test
    void refusalsReadAsCaptured() throws Exception {
        FakePlayer a = FakePlayer.connect(instance, new Pos(910.5, 65, 910.5), "MsD");
        FakePlayer b = FakePlayer.connect(instance, new Pos(912.5, 65, 912.5), "MsE");
        Seen.directory().seen(UUID.randomUUID(), "Gone", 1).get(5, TimeUnit.SECONDS);
        try {
            assertTrue(said(chat(a, "msg gone hi"), "That player is not online!"));
            assertTrue(said(chat(a, "msg zzqxqzz hi"), "Can't find a player by the name of 'zzqxqzz'"));
            Messages.PRIVACY.set(b.player, Audience.NOBODY);
            assertTrue(said(chat(a, "tell MsE hi"), "MsE isn't accepting messages from you."));
            Messages.PRIVACY.set(b.player, Audience.FRIENDS);
            assertTrue(said(chat(a, "tell MsE hi"), "MsE isn't accepting messages from you."));
            Messages.PRIVACY.set(b.player, Audience.EVERYONE);
            assertTrue(said(chat(a, "tell MsE hi"), "To MsE: hi"));
        } finally {
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void aConversationTakesPlainChat() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(920.5, 65, 920.5), "MsF");
        FakePlayer b = FakePlayer.connect(instance, new Pos(922.5, 65, 922.5), "MsG");
        try {
            List<String> opened = chat(a, "msg MsG");
            assertTrue(said(opened, "Opened a chat conversation with MsG for the next 5 minutes. Use /chat a to leave"), opened.toString());
            b.sent.clear();
            assertTrue(Rooms.room(a.player, "psst").audience().isEmpty(), "plain chat leaves the room");
            assertTrue(said(plain(b), "From MsF: psst"), plain(b).toString());

            chat(a, "chat a");
            assertFalse(Rooms.room(a.player, "out loud").audience().isEmpty(), "back to everyone around");
        } finally {
            a.player.remove();
            b.player.remove();
        }
    }
}
