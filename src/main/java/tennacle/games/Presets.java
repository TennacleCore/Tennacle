package tennacle.games;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.MechanicsProfile;
import io.github.term4.polyp.presets.Preset;
import net.minestom.server.codec.Codec;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * {@code mechanics:preset}: the Polyp preset a match's fold starts from ({@code mmc18}, {@code hypixel}, ...), so
 * knockback and the rest follow the platform's ruleset instead of the server global. Empty keeps the mode's own
 * profile. Read by {@link Matches#fold}; this module exists so the rule is declared.
 */
public final class Presets implements GameModule {

    public static final StateKey<String> KEY = StateKey.of("mechanics:preset", Codec.STRING, "");

    /** The builder the fold starts from: the named preset, else the mode's profile. */
    public static @NotNull MechanicsProfile.Builder builderFor(@NotNull Game game, @NotNull Mode mode) {
        String name = game.rules().resolve(Scope.GAME, KEY);
        if (name.isEmpty()) return mode.profile().get();
        for (Preset preset : Preset.values()) {
            if (preset.name().equalsIgnoreCase(name)) return preset.profile().toBuilder();
        }
        throw new IllegalStateException("mechanics:preset '" + name + "' is not a preset - one of "
                + Arrays.stream(Preset.values()).map(p -> p.name().toLowerCase(Locale.ROOT)).toList());
    }

    @Override
    public void install(@NotNull Game game) {} // declaration only

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(KEY);
    }
}
