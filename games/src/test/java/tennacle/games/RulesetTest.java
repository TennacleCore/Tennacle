package tennacle.games;

import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.MechanicsKeys;
import io.github.term4.polyp.MechanicsProfile;
import org.junit.jupiter.api.Test;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.polyp.presets.Preset;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulesetTest extends HeadlessServerTest {

    private static MechanicsProfile base() {
        return Preset.HYPIXEL.profile();
    }

    @Test
    void keepAndWithoutFoldGameless() {
        Ruleset scoped = Ruleset.named("scoped")
                .path("hunger/enabled", false)
                .path(Scope.team("1"), "hunger/enabled", true);
        assertThrows(IllegalStateException.class, () -> scoped.apply(MechanicsProfile.builder(), base()));
        assertDoesNotThrow(() -> scoped.keep(s -> s instanceof Scope.Game).apply(MechanicsProfile.builder(), base()));
        assertDoesNotThrow(() -> scoped.without(Scope.team("1")).apply(MechanicsProfile.builder(), base()));
    }

    @Test
    void overLayersALookOnItsBase() {
        Ruleset base = Ruleset.named("base").path("hunger/enabled", false).path("hunger/naturalRegen", false);
        Ruleset look = Ruleset.named("look").path("hunger/enabled", true).over(base);
        MechanicsProfile.Builder b = MechanicsProfile.builder();
        look.apply(b, base());
        var hunger = b.build().get(MechanicsKeys.HUNGER);
        assertEquals(Boolean.TRUE, hunger.enabled.constantOrNull(), "the look wins where it speaks");
        assertEquals(Boolean.FALSE, hunger.naturalRegen.constantOrNull(), "the base stands where it does not");
        assertEquals("base+look", look.name());
    }

    @Test
    void checkNamesEveryBadEntry() {
        List<String> problems = Ruleset.named("bad").path("nosuch/knob", 1).path("hunger/enabled", "maybe").validate(base());
        assertEquals(2, problems.size(), String.join("; ", problems));
        assertTrue(problems.get(0).contains("unknown member"), problems.get(0));
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> Ruleset.named("bad").path("nosuch/knob", 1).check(base()));
        assertTrue(thrown.getMessage().contains("nosuch/knob"));
    }
}
