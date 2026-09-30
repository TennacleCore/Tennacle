package tennacle.games;

import java.util.Map;
import java.util.HashMap;
import tennacle.games.Ruleset;
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
    /** Each platform's mechanics preset. */
    public static final Map<String, String> MECHANICS = Map.of("hypixel", "hypixel", "scrims", "scrims18", "minemen", "mmc18");

    /** {@code platform}'s ruleset over {@code base} when its whole delta is the mechanics. */
    public static @NotNull Ruleset variant(@NotNull String platform, @NotNull Ruleset base) {
        return Ruleset.named(platform).rule(KEY, MECHANICS.get(platform)).over(base);
    }

    /** Every platform over {@code base}, mechanics alone. */
    public static @NotNull Map<String, Ruleset> variants(@NotNull Ruleset base) {
        Map<String, Ruleset> out = new HashMap<>();
        for (String platform : MECHANICS.keySet()) out.put(platform, variant(platform, base));
        return Map.copyOf(out);
    }

    /** The builder the fold starts from: the named preset, else the mode's profile. */
    public static @NotNull MechanicsProfile.Builder builderFor(@NotNull Game game, @NotNull Mode mode) {
        String name = mode.mechanicsFor(game.rules().resolve(Scope.GAME, KEY));
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
