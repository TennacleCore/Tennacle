package tennacle.games;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameType;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.RuleValues;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.MechanicsProfile;
import io.github.term4.polyp.config.PathEdits;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A named bundle of match settings (docs/rulesets.md): mechanics paths folded into the game's ONE world
 * {@link MechanicsProfile} through {@link PathEdits} (layering, never wiping), and rule values seeded into
 * {@link Game#state()}. Both halves take a grotto {@link Scope}: a team- or seat-scoped path becomes a knob
 * that varies by the context's subject inside that same profile, and a scoped rule is set at that scope
 * where {@code rules().resolve} already finds it. A rule can be named by its key or, from data, by its id -
 * resolved against the type's supported rules at the fold. Immutable; {@link #with} derives a custom variant.
 */
public final class Ruleset {

    private record Path(Scope scope, String path, String value) {}
    private record Rule<T>(Scope scope, StateKey<T> key, T value) {}
    private record DataRule(Scope scope, String id, String value) {}

    private final String name;
    private final List<Path> paths;
    private final List<Rule<?>> rules;
    private final List<DataRule> data;

    private Ruleset(String name, List<Path> paths, List<Rule<?>> rules, List<DataRule> data) {
        this.name = name;
        this.paths = paths;
        this.rules = rules;
        this.data = data;
    }

    public static Ruleset named(@NotNull String name) {
        return new Ruleset(name, List.of(), List.of(), List.of());
    }

    public String name() { return name; }

    public boolean isEmpty() {
        return paths.isEmpty() && rules.isEmpty() && data.isEmpty();
    }

    /** Every problem with the mechanics half against {@code fallback}; rules by id need {@link #validate(MechanicsProfile, GameType)}. */
    public List<String> validate(@Nullable MechanicsProfile fallback) {
        List<String> problems = new ArrayList<>();
        for (Path p : paths) {
            String reason = p.scope() instanceof Scope.Game
                    ? PathEdits.validate(fallback, p.path(), p.value())
                    : validateScoped(fallback, p);
            if (reason != null) problems.add(name + ": " + p.path() + " = " + p.value() + " [" + p.scope().encode() + "]: " + reason);
        }
        return problems;
    }

    /** {@link #validate(MechanicsProfile)} plus the rules by id, against what {@code type} can carry. */
    public List<String> validate(@Nullable MechanicsProfile fallback, @NotNull GameType type) {
        List<String> problems = validate(fallback);
        Map<String, StateKey<?>> supported = type.supportedRules();
        for (DataRule d : data) {
            StateKey<?> key = supported.get(d.id());
            if (key == null) {
                problems.add(name + ": " + d.id() + " is not a rule of " + type.id() + " (known: " + supported.keySet() + ")");
                continue;
            }
            try {
                RuleValues.parse(key, d.value());
            } catch (IllegalArgumentException e) {
                problems.add(name + ": " + d.id() + " = " + d.value() + " [" + d.scope().encode() + "]: " + e.getMessage());
            }
        }
        return problems;
    }

    private static @Nullable String validateScoped(@Nullable MechanicsProfile fallback, Path p) {
        try {
            PathEdits.apply(MechanicsProfile.builder(), fallback, p.path(), p.value(), player -> false);
            return null;
        } catch (RuntimeException e) {
            return e.getMessage() != null ? e.getMessage() : e.toString();
        }
    }

    /** {@link #validate} as an install-time gate: a bad entry stops the mode before any match can find it. */
    public Ruleset check(@Nullable MechanicsProfile fallback) {
        return checked(validate(fallback));
    }

    public Ruleset check(@Nullable MechanicsProfile fallback, @NotNull GameType type) {
        return checked(validate(fallback, type));
    }

    private Ruleset checked(List<String> problems) {
        if (!problems.isEmpty()) throw new IllegalStateException("bad ruleset entries:\n" + String.join("\n", problems));
        return this;
    }

    /** This ruleset's entries over {@code base}'s: a look over its mode's base is ONE ruleset to fold, seed and check. */
    public Ruleset over(@NotNull Ruleset base) {
        Ruleset merged = base;
        for (Path p : paths) merged = merged.path(p.scope(), p.path(), p.value());
        for (Rule<?> r : rules) merged = merged.add(r);
        for (DataRule d : data) merged = merged.rule(d.scope(), d.id(), d.value());
        return new Ruleset(base.name + "+" + name, merged.paths, merged.rules, merged.data);
    }

    private <T> Ruleset add(Rule<T> r) {
        return rule(r.scope(), r.key(), r.value());
    }

    public Ruleset path(@NotNull String path, @NotNull Object value) {
        return path(Scope.GAME, path, value);
    }

    public Ruleset path(@NotNull Scope scope, @NotNull String path, @NotNull Object value) {
        List<Path> next = new ArrayList<>(paths);
        next.removeIf(p -> p.scope().equals(scope) && p.path().equals(path));
        next.add(new Path(scope, path, String.valueOf(value)));
        return new Ruleset(name, List.copyOf(next), rules, data);
    }

    public <T> Ruleset rule(@NotNull StateKey<T> key, @NotNull T value) {
        return rule(Scope.GAME, key, value);
    }

    public <T> Ruleset rule(@NotNull Scope scope, @NotNull StateKey<T> key, @NotNull T value) {
        List<Rule<?>> next = new ArrayList<>(rules);
        next.removeIf(r -> r.scope().equals(scope) && r.key().equals(key));
        next.add(new Rule<>(scope, key, value));
        // one entry per scope and key whichever form named it: a typed rule displaces the same rule by id
        List<DataRule> nextData = new ArrayList<>(data);
        nextData.removeIf(d -> d.scope().equals(scope) && d.id().equals(key.id()));
        return new Ruleset(name, paths, List.copyOf(next), List.copyOf(nextData));
    }

    /** A rule by id, as data ({@code score:target = 3}); the key and the value resolve against the game's type at the fold. */
    public Ruleset rule(@NotNull String id, @NotNull String value) {
        return rule(Scope.GAME, id, value);
    }

    public Ruleset rule(@NotNull Scope scope, @NotNull String id, @NotNull String value) {
        List<DataRule> next = new ArrayList<>(data);
        next.removeIf(d -> d.scope().equals(scope) && d.id().equals(id));
        next.add(new DataRule(scope, id, value));
        List<Rule<?>> nextRules = new ArrayList<>(rules);
        nextRules.removeIf(r -> r.scope().equals(scope) && r.key().id().equals(id));
        return new Ruleset(name, paths, List.copyOf(nextRules), List.copyOf(next));
    }

    public Ruleset with(@NotNull String path, @NotNull Object value) {
        return path(path, value);
    }

    public Ruleset with(@NotNull Scope scope, @NotNull String path, @NotNull Object value) {
        return path(scope, path, value);
    }

    /** Everything but the entries at {@code scope} - dropping a team's handicap re-folds from the base. */
    public Ruleset without(@NotNull Scope scope) {
        return keep(s -> !s.equals(scope));
    }

    /** Everything but {@code id}'s entry (a rule or a path) at {@code scope}: the scope above shows through again. */
    public Ruleset without(@NotNull Scope scope, @NotNull String id) {
        return new Ruleset(name,
                paths.stream().filter(p -> !(p.scope().equals(scope) && p.path().equals(id))).toList(),
                rules.stream().filter(r -> !(r.scope().equals(scope) && r.key().id().equals(id))).toList(),
                data.stream().filter(d -> !(d.scope().equals(scope) && d.id().equals(id))).toList());
    }

    /** Whether an entry for {@code id} stands at {@code scope}. */
    public boolean names(@NotNull Scope scope, @NotNull String id) {
        return paths.stream().anyMatch(p -> p.scope().equals(scope) && p.path().equals(id))
                || rules.stream().anyMatch(r -> r.scope().equals(scope) && r.key().id().equals(id))
                || data.stream().anyMatch(d -> d.scope().equals(scope) && d.id().equals(id));
    }

    /** Only the entries whose scope {@code test} accepts - {@code s -> s instanceof Scope.Game} is "game-wide only". */
    public Ruleset keep(@NotNull Predicate<Scope> test) {
        return new Ruleset(name,
                paths.stream().filter(p -> test.test(p.scope())).toList(),
                rules.stream().filter(r -> test.test(r.scope())).toList(),
                data.stream().filter(d -> test.test(d.scope())).toList());
    }

    /** Folds the game-wide mechanics paths; a scoped entry needs {@link #apply(MechanicsProfile.Builder, MechanicsProfile, Game)}. */
    public void apply(@NotNull MechanicsProfile.Builder b, @Nullable MechanicsProfile fallback) {
        for (Path p : paths) {
            if (!(p.scope() instanceof Scope.Game)) {
                throw new IllegalStateException(name + ": '" + p.path() + "' is scoped to " + p.scope().encode()
                        + " and needs the game to fold");
            }
            PathEdits.apply(b, fallback, p.path(), p.value());
        }
    }

    /** Game-wide entries first, then the targeted ones as per-subject values inside the same profile. */
    public void apply(@NotNull MechanicsProfile.Builder b, @Nullable MechanicsProfile fallback, @NotNull Game game) {
        for (Path p : paths) {
            if (p.scope() instanceof Scope.Game) PathEdits.apply(b, fallback, p.path(), p.value());
        }
        for (Path p : paths) {
            if (p.scope() instanceof Scope.Game) continue;
            requireScope(game, p.scope(), name + ": " + p.path());
            PathEdits.apply(b, fallback, p.path(), p.value(), targeting(game, p.scope()));
        }
    }

    /** Seeds every rule into the game's state at its scope; a rule by id resolves through the type's supported rules. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void seed(@NotNull Game game) {
        for (Rule<?> rule : rules) {
            requireScope(game, rule.scope(), name + ": " + rule.key().id());
            game.state().set(rule.scope(), (StateKey) rule.key(), rule.value());
        }
        Map<String, StateKey<?>> supported = game.type().supportedRules();
        for (DataRule d : data) {
            StateKey<?> key = supported.get(d.id());
            if (key == null) {
                throw new IllegalStateException(name + ": " + d.id() + " is not a rule of " + game.type().id()
                        + " (known: " + supported.keySet() + ")");
            }
            requireScope(game, d.scope(), name + ": " + d.id());
            game.state().set(d.scope(), (StateKey) key, RuleValues.parse(key, d.value()));
        }
    }

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

    // a typo'd team or seat must fail the launch, not quietly apply to nobody
    private static void requireScope(Game game, Scope scope, String what) {
        switch (scope) {
            case Scope.Team team -> {
                if (game.roster().teams().stream().noneMatch(t -> t.id().equals(team.id()))) {
                    throw new IllegalStateException(what + " targets team '" + team.id() + "', which this roster lacks");
                }
            }
            case Scope.Slot slot -> {
                if (game.roster().slot(slot.index()) == null) {
                    throw new IllegalStateException(what + " targets seat " + slot.index() + ", which this roster lacks");
                }
            }
            default -> { }
        }
    }
}
