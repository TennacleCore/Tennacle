package tennacle.access;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.vault.MemoryVault;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.PlayerPrefs;
import tennacle.Seen;
import tennacle.commands.Commands;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessCommandsTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        MemoryVault vault = new MemoryVault();
        PlayerPrefs.vault(() -> vault);
        Access.install();
        Commands.install();
        Access.owners(Set.of("RoleBoss"));
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

    @Test
    void anOwnerHandsOutRoles() {
        FakePlayer boss = FakePlayer.connect(instance, new Pos(10.5, 65, 0.5), "RoleBoss");
        FakePlayer t = FakePlayer.connect(instance, new Pos(12.5, 65, 0.5), "RoleT");
        try {
            assertTrue(Commands.visible(boss.player, "role"));
            assertFalse(Commands.visible(t.player, "role"), "a player never sees it");
            assertEquals(List.of("Unknown command. Type /help for help."), chat(t, "role RoleBoss admin"));

            t.sent.clear();
            assertTrue(said(chat(boss, "role RoleT mod"), "RoleT is now a Mod."));
            assertTrue(said(plain(t), "You are now a Mod."), plain(t).toString());
            assertTrue(Access.allows(t.player, "tennacle.moderate.mute"));
            assertTrue(said(chat(boss, "role RoleT"), "RoleT is a Mod."));

            assertTrue(said(chat(boss, "grant RoleT tennacle.dev.audit"), "RoleT now holds tennacle.dev.audit."));
            assertTrue(Access.allows(t.player, "tennacle.dev.audit"));
            assertTrue(said(chat(boss, "role RoleT"), "On top: tennacle.dev.audit."));
            assertTrue(said(chat(boss, "revoke RoleT tennacle.dev.audit"), "RoleT no longer holds tennacle.dev.audit."));
            assertFalse(Access.allows(t.player, "tennacle.dev.audit"));
            assertTrue(said(chat(boss, "revoke RoleT tennacle.moderate.mute"), "wasn't granted tennacle.moderate.mute on its own"));
            assertTrue(said(chat(boss, "grant RoleT tennacle..x"), "isn't a permission"));
            assertTrue(said(chat(boss, "role RoleBoss"), "Owner (from the server config)"));
        } finally {
            boss.player.remove();
            t.player.remove();
        }
    }

    @Test
    void anOfflinePlayerCanBeSet() throws Exception {
        FakePlayer boss = FakePlayer.connect(instance, new Pos(14.5, 65, 0.5), "RoleBoss");
        UUID away = UUID.randomUUID();
        Seen.directory().seen(away, "Gone", 1).get(5, TimeUnit.SECONDS);
        try {
            assertTrue(said(chat(boss, "role gone builder"), "Gone is now a Builder."));
            assertEquals(Role.BUILDER, Access.grants(away).get(5, TimeUnit.SECONDS).role());
            assertTrue(said(chat(boss, "role nobodyhere"), "No player found with name nobodyhere."));
        } finally {
            boss.player.remove();
        }
    }
}
