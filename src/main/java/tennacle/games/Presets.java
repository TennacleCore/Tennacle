package tennacle.games;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.MechanicsKeys;
import io.github.term4.polyp.MechanicsProfile;
import io.github.term4.polyp.presets.Preset;
import net.minestom.server.codec.Codec;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * {@code match.mechanics}: the Polyp preset a match's fold starts from ({@code mmc18}, {@code hypixel}, ...), so
 * knockback and the rest follow the platform's ruleset instead of the server global. Empty keeps the mode's own
 * profile. Read by {@link Matches#fold}; this module exists so the rule is declared. One of the four names a
 * match runs under: the preset a player asks for ({@link Matches#PLATFORM}), the ruleset it picks
 * ({@link Matches#RULES}), these mechanics, and the look ({@link Matches#LOOK}).
 */
public final class Presets implements GameModule {

    public static final StateKey<String> KEY = StateKey.of("match.mechanics", Codec.STRING, "");

    /** The builder the fold starts from: the named preset, else the mode's profile. */
    public static @NotNull MechanicsProfile.Builder builderFor(@NotNull Game game, @NotNull Mode mode) {
        String name = game.rules().resolve(Scope.GAME, KEY);
        if (name.isEmpty()) return mode.profile().get();
        for (Preset preset : Preset.values()) {
            // the compat layer rides with it: a preset's mechanics and its cross-version layer are one entry
            if (preset.name().equalsIgnoreCase(name)) {
                return preset.profile().toBuilder().set(MechanicsKeys.COMPAT, preset.compat());
            }
        }
        throw new IllegalStateException("match.mechanics '" + name + "' is not a preset - one of "
                + Arrays.stream(Preset.values()).map(p -> p.name().toLowerCase(Locale.ROOT)).toList());
    }

    @Override
    public void install(@NotNull Game game) {} // declaration only

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(KEY);
    }
}
