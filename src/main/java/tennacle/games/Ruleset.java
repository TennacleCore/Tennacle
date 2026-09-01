package tennacle.games;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.MechanicsProfile;
import io.github.term4.polyp.config.PathEdits;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A named bundle of match settings (docs/rulesets.md): mechanics paths applied to the game's
 * {@link MechanicsProfile} through {@link PathEdits} (layering, never wiping), and rule values seeded
 * into {@link Game#state()}. Immutable; {@link #with} derives a custom variant.
 */
public final class Ruleset {

    private record Rule<T>(StateKey<T> key, T value) {}

    private final String name;
    private final Map<String, String> paths;
    private final List<Rule<?>> rules;

    private Ruleset(String name, Map<String, String> paths, List<Rule<?>> rules) {
        this.name = name;
        this.paths = paths;
        this.rules = rules;
    }

    public static Ruleset named(@NotNull String name) {
        return new Ruleset(name, Map.of(), List.of());
    }

    public String name() { return name; }

    /** A mechanics path write ({@code member[/typeKey]/knob}); the value serializes via {@code String.valueOf}. */
    public Ruleset path(@NotNull String path, @NotNull Object value) {
        Map<String, String> next = new LinkedHashMap<>(paths);
        next.put(path, String.valueOf(value));
        return new Ruleset(name, next, rules);
    }

    /** A rule value seeded into game state at launch (modules read their StateKeys as always). */
    public <T> Ruleset rule(@NotNull StateKey<T> key, @NotNull T value) {
        List<Rule<?>> next = new ArrayList<>(rules);
        next.add(new Rule<>(key, value));
        return new Ruleset(name, paths, next);
    }

    /** Derives an override variant ("scrims + bow crits"): same settings, one path replaced. */
    public Ruleset with(@NotNull String path, @NotNull Object value) {
        return path(path, value);
    }

    /** Folds the mechanics half onto {@code b}, layering over its members else {@code fallback}'s. */
    public void apply(@NotNull MechanicsProfile.Builder b, MechanicsProfile fallback) {
        paths.forEach((path, value) -> PathEdits.apply(b, fallback, path, value));
    }

    /** Seeds the rule half into the game (game scope). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void seed(@NotNull Game game) {
        for (Rule<?> rule : rules) game.state().set(Scope.GAME, (StateKey) rule.key(), rule.value());
    }
}
