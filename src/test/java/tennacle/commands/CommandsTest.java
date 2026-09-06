package tennacle.commands;

import tennacle.games.TestWiring;
import tennacle.lobby.Welcome;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The registry answers /help, a bare command, the welcome, and a command that throws. */
class CommandsTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        AppCommand broken = Commands.dev("brokentool", "Throws on purpose.");
        broken.setDefaultExecutor((sender, ctx) -> {
            throw new IllegalStateException("kaboom");
        });
        Commands.register(broken);
        Commands.register(Commands.dev("silenttool", "Answers with its usage.").usage("/silenttool <x>", "does x"));
    }

    private static List<String> chat(FakePlayer p, String command) {
        p.sent.clear();
        MinecraftServer.getCommandManager().execute(p.player, command);
        return p.sent(SystemChatPacket.class).stream()
                .<String>map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }

    @Test
    void helpListsAndDetails() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(700.5, 65, 700.5), "HelpA");
        try {
            List<String> page = chat(a, "help");
            assertTrue(page.stream().anyMatch(l -> l.contains("/duel") && l.contains("Challenge a player")), "the listing: " + page);
            assertTrue(page.stream().noneMatch(l -> l.contains("/brokentool")), "dev tools stay off the player page: " + page);
            boolean listed = false;
            for (int devPage = 1; devPage <= 5 && !listed; devPage++) {
                listed = chat(a, "help dev " + devPage).stream().anyMatch(l -> l.contains("/brokentool"));
            }
            assertTrue(listed, "and sit on the dev pages");
            List<String> one = chat(a, "help duel");
            assertTrue(one.stream().anyMatch(l -> l.contains("/duel accept <player>")), "one command's forms: " + one);
            List<String> none = chat(a, "help nosuch");
            assertTrue(none.stream().anyMatch(l -> l.contains("No command named nosuch")), none.toString());
        } finally {
            a.player.remove();
        }
    }

    @Test
    void aBareCommandAnswersWithUsage() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(702.5, 65, 702.5), "HelpB");
        try {
            List<String> sent = chat(a, "silenttool");
            assertTrue(sent.stream().anyMatch(l -> l.contains("/silenttool <x>") && l.contains("does x")), sent.toString());
        } finally {
            a.player.remove();
        }
    }

    @Test
    void aThrowingCommandReportsItself() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(704.5, 65, 704.5), "HelpC");
        try {
            List<String> sent = chat(a, "brokentool");
            assertTrue(sent.stream().anyMatch(l -> l.contains("Something went wrong")), sent.toString());
        } finally {
            a.player.remove();
        }
    }

    @Test
    void theWelcomeListsFeatured() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(706.5, 65, 706.5), "HelpD");
        try {
            a.sent.clear();
            Welcome.send(a.player);
            List<String> sent = a.sent(SystemChatPacket.class).stream()
                    .<String>map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
            assertTrue(sent.stream().anyMatch(l -> l.contains("Tennacle") && l.contains("/duel") && l.contains("/help")),
                    "the name, the featured commands, the way to the rest: " + sent);
        } finally {
            a.player.remove();
        }
    }
}
