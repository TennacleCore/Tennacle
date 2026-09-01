package tennacle.games;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.MechanicsProfile;
import io.github.term4.polyp.config.PathEdits;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A named bundle of match settings (docs/rulesets.md): mechanics paths folded into the game's ONE world
 * {@link MechanicsProfile} through {@link PathEdits} (layering, never wiping), and rule values seeded into
 * {@link Game#state()}. Both halves take a grotto {@link Scope}: a team- or seat-scoped path becomes a knob
 * that varies by the context's subject inside that same profile, and a scoped rule is set at that scope
 * where {@code rules().resolve} already finds it. Immutable; {@link #with} derives a custom variant.
 */
public final class Ruleset {

    private record Path(Scope scope, String path, String value) {}
    private record Rule<T>(Scope scope, StateKey<T> key, T value) {}

    private final String name;
    private final List<Path> paths;
    private final List<Rule<?>> rules;

    private Ruleset(String name, List<Path> paths, List<Rule<?>> rules) {
        this.name = name;
        this.paths = paths;
        this.rules = rules;
    }

    public static Ruleset named(@NotNull String name) {
        return new Ruleset(name, List.of(), List.of());
    }

    public String name() { return name; }

    /** A game-wide mechanics path write ({@code member[/typeKey]/knob}); the value serializes via {@code String.valueOf}. */
    public Ruleset path(@NotNull String path, @NotNull Object value) {
        return path(Scope.GAME, path, value);
    }

    /** A mechanics path write for one scope: {@code Scope.team("red")} makes red's arrows differ, nobody else's. */
    public Ruleset path(@NotNull Scope scope, @NotNull String path, @NotNull Object value) {
        List<Path> next = new ArrayList<>(paths);
        next.removeIf(p -> p.scope().equals(scope) && p.path().equals(path));
        next.add(new Path(scope, path, String.valueOf(value)));
        return new Ruleset(name, List.copyOf(next), rules);
    }

    /** A rule value seeded at game scope (modules read their StateKeys as always). */
    public <T> Ruleset rule(@NotNull StateKey<T> key, @NotNull T value) {
        return rule(Scope.GAME, key, value);
    }

    /** A rule value seeded at {@code scope} - a team's own respawn delay, a seat's own kit. */
    public <T> Ruleset rule(@NotNull Scope scope, @NotNull StateKey<T> key, @NotNull T value) {
        List<Rule<?>> next = new ArrayList<>(rules);
        next.add(new Rule<>(scope, key, value));
        return new Ruleset(name, paths, List.copyOf(next));
    }

    /** Derives an override variant ("scrims + bow crits"): same settings, one path replaced. */
    public Ruleset with(@NotNull String path, @NotNull Object value) {
        return path(path, value);
    }

    public Ruleset with(@NotNull Scope scope, @NotNull String path, @NotNull Object value) {
        return path(scope, path, value);
    }

    /**
     * Every entry - path and rule - except those at {@code scope}: "red's handicap is off". Mid-game that is a
     * RE-FOLD over the base profile with the trimmed ruleset, never surgery on the built one, which is why a
     * scoped knob wraps the game-wide value rather than replacing it.
     */
    public Ruleset without(@NotNull Scope scope) {
        return retaining(s -> !s.equals(scope));
    }

    /** Only the entries whose scope {@code keep} accepts - {@code s -> s instanceof Scope.Game} is "game-wide only". */
    public Ruleset retaining(@NotNull Predicate<Scope> keep) {
        return new Ruleset(name,
                paths.stream().filter(p -> keep.test(p.scope())).toList(),
                rules.stream().filter(r -> keep.test(r.scope())).toList());
    }

    /** Folds the game-wide mechanics half onto {@code b}; a scoped entry needs a game - use {@link #apply(MechanicsProfile.Builder, MechanicsProfile, Game)}. */
    public void apply(@NotNull MechanicsProfile.Builder b, @Nullable MechanicsProfile fallback) {
        for (Path p : paths) {
            if (!(p.scope() instanceof Scope.Game)) {
                throw new IllegalStateException(name + ": '" + p.path() + "' is scoped to " + p.scope().encode()
                        + " and needs the game to fold");
            }
            PathEdits.apply(b, fallback, p.path(), p.value());
        }
    }

    /**
     * Folds every mechanics entry onto {@code b}. Game-wide entries first, then scoped ones, so a scoped knob
     * wraps the FINAL game-wide value as its fallback regardless of declaration order.
     */
    public void apply(@NotNull MechanicsProfile.Builder b, @Nullable MechanicsProfile fallback, @NotNull Game game) {
        for (Path p : paths) {
            if (p.scope() instanceof Scope.Game) PathEdits.apply(b, fallback, p.path(), p.value());
        }
        for (Path p : paths) {
            if (!(p.scope() instanceof Scope.Game)) PathEdits.apply(b, fallback, p.path(), p.value(), targeting(game, p.scope()));
        }
    }

    /** Seeds the rule half into the game, each at its scope. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void seed(@NotNull Game game) {
        for (Rule<?> rule : rules) game.state().set(rule.scope(), (StateKey) rule.key(), rule.value());
    }

    /** A scope as the players it names, read live off the roster - a substitution or team switch just works. */
    private static Predicate<Player> targeting(Game game, Scope scope) {
        return switch (scope) {
            case Scope.Team team -> player -> {
                Slot seat = game.roster().slotOf(player);
                return seat != null && seat.team().equals(team.id());
            };
            case Scope.Slot slot -> player -> {
                Slot seat = game.roster().slotOf(player);
                return seat != null && seat.index() == slot.index();
            };
            case Scope.Game g -> throw new IllegalStateException("game scope is not targeted");
            case Scope.Ent e -> throw new IllegalArgumentException("mechanics cannot target an entity scope: " + e.encode());
        };
    }
}
