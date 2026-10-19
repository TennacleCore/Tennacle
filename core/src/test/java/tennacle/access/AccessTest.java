package tennacle.access;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.vault.MemoryVault;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.PlayerPrefs;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessTest extends HeadlessServerTest {

    private static final List<Player> heard = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void wire() {
        MemoryVault vault = new MemoryVault();
        PlayerPrefs.vault(() -> vault);
        Access.install();
        Access.onChange(heard::add);
    }

    @Test
    void aRoleHoldsTheLadderBelow() {
        Set<String> mod = Access.effective(new Grants(Role.MOD, Set.of()));
        assertTrue(Access.matches(mod, "tennacle.build.arena"), "a mod builds");
        assertTrue(Access.matches(mod, "tennacle.moderate.kick"));
        assertFalse(Access.matches(mod, "tennacle.staff.audit"));
        assertTrue(Access.matches(Access.effective(new Grants(Role.ADMIN, Set.of())), "tennacle.staff.audit"));
        assertFalse(Access.matches(Access.effective(Grants.NONE), "tennacle.build.arena"));
    }

    @Test
    void aWildcardHoldsItsBranch() {
        Set<String> held = Set.of("a.b.*");
        assertTrue(Access.matches(held, "a.b"));
        assertTrue(Access.matches(held, "a.b.c.d"));
        assertFalse(Access.matches(held, "a.bc"));
        assertFalse(Access.matches(held, "a"));
    }

    @Test
    void grantsFollowAtOnce() throws Exception {
        FakePlayer p = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "AccA");
        UUID id = p.player.getUuid();
        try {
            assertFalse(Access.allows(p.player, "tennacle.moderate.kick"));
            heard.clear();
            Access.grant(id, "tennacle.moderate.kick").get(5, TimeUnit.SECONDS);
            assertTrue(Access.allows(p.player, "tennacle.moderate.kick"));
            assertEquals(List.of(p.player), heard, "an online change is heard");
            Access.revoke(id, "tennacle.moderate.kick").get(5, TimeUnit.SECONDS);
            assertFalse(Access.allows(p.player, "tennacle.moderate.kick"));
            Access.role(id, Role.MOD).get(5, TimeUnit.SECONDS);
            assertTrue(Access.allows(p.player, "tennacle.moderate.anything"));
            assertTrue(Access.allows(p.player, "tennacle.build.arena"));
        } finally {
            p.player.remove();
        }
    }

    @Test
    void anOfflineChangeIsKept() throws Exception {
        UUID away = UUID.randomUUID();
        heard.clear();
        Access.role(away, Role.BUILDER).get(5, TimeUnit.SECONDS);
        Access.grant(away, "tennacle.moderate.mute").get(5, TimeUnit.SECONDS);
        Grants stored = Access.grants(away).get(5, TimeUnit.SECONDS);
        assertEquals(new Grants(Role.BUILDER, Set.of("tennacle.moderate.mute")), stored);
        assertTrue(heard.isEmpty(), "nobody online to tell");
    }

    @Test
    void everyoneHoldsTheServerWide() {
        FakePlayer p = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "AccB");
        Access.everyone(Set.of("tennacle.staff.*"));
        try {
            assertTrue(Access.allows(p.player, "tennacle.staff.audit"));
            assertFalse(Access.allows(p.player, "tennacle.build.arena"));
        } finally {
            Access.everyone(Set.of());
            p.player.remove();
        }
    }
}
