package tennacle.commands;

import tennacle.Mechanics;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.polyp.MechanicsKeys;
import io.github.term4.polyp.MechanicsProfile;
import io.github.term4.polyp.Polyp;
import io.github.term4.polyp.platform.compatibility.CompatConfig;
import io.github.term4.polyp.platform.compatibility.CompatConfig.CompatContext;
import io.github.term4.polyp.presets.Preset;
import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@code /preset} promises: a scope's preset answers for whoever stands in it, and the app's own layer rides along. */
class PresetScopeTest extends HeadlessServerTest {

    private static final CompatContext NOBODY = new CompatContext(null); // no targeted members here: the untargeted value is the one

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void theAppLayerSurvives() {
        MechanicsProfile modern = Mechanics.profileOf(Preset.VANILLA);
        assertNotNull(modern.get(MechanicsKeys.FIXES), "a preset swap must not drop the server's fix set");

        CompatConfig off = modern.get(MechanicsKeys.COMPAT);
        assertNotNull(off);
        assertFalse(off.legacyHitbox(NOBODY), "VANILLA carries its own compat, which is none");

        CompatConfig on = Mechanics.profileOf(Preset.VANILLA18).get(MechanicsKeys.COMPAT);
        assertNotNull(on);
        assertTrue(on.legacyHitbox(NOBODY), "VANILLA18 carries the 1.8 layer");
    }

    @Test
    void theLobbyScopeWins() {
        Polyp polyp = Mechanics.polyp;
        MechanicsProfile globalBefore = polyp.profiles().global();
        MechanicsProfile lobbyBefore = polyp.profiles().instance(instance);
        FakePlayer probe = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "PresetProbe");
        try {
            polyp.profiles().setGlobal(Mechanics.profileOf(Preset.VANILLA18));
            polyp.profiles().setInstance(instance, Mechanics.profileOf(Preset.VANILLA));
            assertFalse(polyp.profiles().resolve(probe.player, MechanicsKeys.COMPAT).legacyHitbox(NOBODY),
                    "the lobby's preset answers for a player standing in it");

            polyp.profiles().setInstance(instance, null);
            assertTrue(polyp.profiles().resolve(probe.player, MechanicsKeys.COMPAT).legacyHitbox(NOBODY),
                    "reset falls back to global");
        } finally {
            polyp.profiles().setInstance(instance, lobbyBefore);
            polyp.profiles().setGlobal(globalBefore);
            probe.player.remove();
        }
    }
}
