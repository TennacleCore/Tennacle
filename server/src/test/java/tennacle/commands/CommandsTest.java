package tennacle.commands;

import tennacle.lobby.Welcome;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.network.packet.server.play.DeclareCommandsPacket;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import tennacle.access.Access;
import tennacle.server.ServerWiring;

/** The registry answers /help, a bare command, the welcome, and a command that throws. */
class CommandsTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        ServerWiring.ensure(instance);
        AppCommand broken = Commands.staff("brokentool", "Throws on purpose.");
        broken.setDefaultExecutor((sender, ctx) -> {
            throw new IllegalStateException("kaboom");
        });
        Commands.register(broken);
        Commands.register(Commands.staff("silenttool", "Answers with its usage.").usage("/silenttool <x>", "does x"));
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
            assertTrue(page.stream().noneMatch(l -> l.contains("/brokentool")), "staff tools stay off the player page: " + page);
            boolean listed = false;
            for (int devPage = 1; devPage <= 5 && !listed; devPage++) {
                listed = chat(a, "help staff " + devPage).stream().anyMatch(l -> l.contains("/brokentool"));
            }
            assertTrue(listed, "and sit on the staff pages");
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
    void aHiddenCommandReadsUnknown() throws Exception {
        FakePlayer a = FakePlayer.connect(instance, new Pos(708.5, 65, 708.5), "HelpH");
        Access.everyone(Set.of());
        try {
            assertFalse(Commands.visible(a.player, "brokentool"));
            assertEquals(List.of("Unknown command. Type /help for help."), chat(a, "brokentool"), "once");
            assertEquals(List.of("Unknown command. Type /help for help."), chat(a, "nosuchthing"), "the same as one that isn't there");
            assertTrue(chat(a, "help staff").contains("You have no staff tools."));
            assertTrue(Commands.visible(a.player, "help"), "a player command stays");

            a.sent.clear();
            Access.grant(a.player.getUuid(), "tennacle.staff.brokentool").get(5, TimeUnit.SECONDS);
            assertTrue(Commands.visible(a.player, "brokentool"), "a grant shows it");
            assertFalse(a.sent(DeclareCommandsPacket.class).isEmpty(), "and the command list is sent again");
        } finally {
            Access.everyone(Set.of("tennacle.staff.*"));
            a.player.remove();
        }
    }

    @Test
    void gamemodeShortcutsAndTargets() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(710.5, 65, 710.5), "ModeA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(712.5, 65, 712.5), "ModeB");
        try {
            chat(a, "gmsp");
            assertEquals(GameMode.SPECTATOR, a.player.getGameMode());
            chat(a, "gamemode c");
            assertEquals(GameMode.CREATIVE, a.player.getGameMode());
            chat(a, "gm 2");
            assertEquals(GameMode.ADVENTURE, a.player.getGameMode());
            chat(a, "gma ModeB");
            assertEquals(GameMode.ADVENTURE, b.player.getGameMode(), "a player's, named");
            chat(a, "gm survival ModeB");
            assertEquals(GameMode.SURVIVAL, b.player.getGameMode());
            Access.everyone(Set.of());
            assertEquals(List.of("Unknown command. Type /help for help."), chat(b, "gmc"), "a player without the node");
        } finally {
            Access.everyone(Set.of("tennacle.staff.*"));
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void theWelcomePointsToHelp() {
        FakePlayer a = FakePlayer.connect(instance, new Pos(706.5, 65, 706.5), "HelpD");
        try {
            a.sent.clear();
            Welcome.send(a.player);
            List<String> sent = a.sent(SystemChatPacket.class).stream()
                    .<String>map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
            assertEquals(List.of("Welcome to Tennacle! Type /help to see what you can do."), sent);
        } finally {
            a.player.remove();
        }
    }
}
