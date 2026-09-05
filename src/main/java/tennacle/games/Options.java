package tennacle.games;

import tennacle.Mechanics;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.state.StateKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * What a request asks for past the mode: the words of {@code /duel <player> <mode> [map] [-flag ...]}. A bare
 * word is the map, from the mode's pool. A {@code -name=value} word is a {@link Flag} by name ({@code -name}
 * alone passes {@code true}); a name that is no flag but reads as a rule id or a mechanics path lands as that
 * rule or path directly. What the flags set - rules, paths, a look, a map, code at launch - is folded over the
 * platform's ruleset at launch as the game's first live modifier, so {@code /rules} layers over it the same way
 * it layers over anything. Everything is checked here, so a bad word answers the requester rather than failing
 * the launch the accepter triggers.
 */
public record Options(@Nullable String map, @Nullable String look, @Nullable String platform,
                      @NotNull Ruleset rules, @NotNull List<Consumer<Game>> atLaunch, @NotNull List<String> words) {

    public static final Options NONE = new Options(null, null, null, Ruleset.named("flags"), List.of(), List.of());

    /** What a flag lands in. */
    public static final class Builder {
        private final Mode mode;
        private String map, look, platform;
        private Ruleset rules = Ruleset.named("flags");
        private final List<Consumer<Game>> atLaunch = new ArrayList<>();

        Builder(Mode mode) {
            this.mode = mode;
        }

        public @NotNull Builder map(@NotNull Mode mode, @NotNull String name) {
            if (mode.arena().named(name) == null) {
                throw new IllegalArgumentException("no map named " + name + " for " + mode.name()
                        + " (" + String.join(", ", mode.arena().names()) + ")");
            }
            this.map = name;
            return this;
        }

        public @NotNull Builder look(@NotNull String name) { this.look = name; return this; }
        public @NotNull Builder platform(@NotNull String name) { this.platform = name; return this; }
        public <T> @NotNull Builder rule(@NotNull StateKey<T> key, @NotNull T value) { rules = rules.rule(key, value); return this; }
        public @NotNull Builder rule(@NotNull String id, @NotNull String raw) { rules = rules.rule(id, raw); return this; }
        public @NotNull Builder path(@NotNull String path, @NotNull String raw) { rules = rules.path(path, raw); return this; }
        /** Code that runs on the launched game, after its ruleset is folded - what no rule expresses. */
        public @NotNull Builder atLaunch(@NotNull Consumer<Game> hook) { atLaunch.add(hook); return this; }

        Options build(List<String> words) {
            List<String> problems = rules.validate(Mechanics.polyp.profiles().global(), mode.type());
            if (!problems.isEmpty()) throw new IllegalArgumentException(String.join("; ", problems));
            return new Options(map, look, platform, rules, List.copyOf(atLaunch), List.copyOf(words));
        }
    }

    public static @NotNull Options parse(@NotNull Mode mode, @NotNull List<String> words) {
        Builder into = new Builder(mode);
        for (String word : words) {
            if (!word.startsWith("-")) {
                if (into.map != null) throw new IllegalArgumentException("one map, not " + into.map + " and " + word);
                into.map(mode, word);
                continue;
            }
            String body = word.substring(1);
            int eq = body.indexOf('=');
            String name = eq < 0 ? body : body.substring(0, eq);
            String value = eq < 0 ? "true" : body.substring(eq + 1);
            if (name.isEmpty()) throw new IllegalArgumentException("a flag needs a name: " + word);
            Flag flag = Flags.get(name);
            if (flag != null) {
                if (!Flags.offers(mode, flag)) {
                    throw new IllegalArgumentException("-" + name + " is not a flag of " + mode.name() + " (" + flagNames(mode) + ")");
                }
                flag.apply(mode, value, into);
            } else if (name.indexOf('/') >= 0) {
                into.path(name, value);
            } else if (name.indexOf('.') >= 0) {
                into.rule(name, value);
            } else {
                throw new IllegalArgumentException("unknown flag -" + name + " (" + flagNames(mode) + "; a rule id or a mechanics path also lands)");
            }
        }
        return into.build(words);
    }

    private static String flagNames(Mode mode) {
        return String.join(", ", Flags.of(mode).stream().map(f -> "-" + f.name()).toList());
    }

    /**
     * What the tail can hold at {@code token}: map names bare; flag names after a dash, and rule ids once a dot
     * says the raw form is meant; a flag's values after its equals sign.
     */
    static @NotNull Collection<String> completions(@NotNull Mode mode, @NotNull String token) {
        if (!token.startsWith("-")) return mode.arena().names();
        String body = token.substring(1);
        int eq = body.indexOf('=');
        if (eq < 0) {
            List<String> names = new ArrayList<>();
            for (Flag flag : Flags.of(mode)) names.add("-" + flag.name());
            if (body.indexOf('.') >= 0) for (String id : mode.type().supportedRules().keySet()) names.add("-" + id);
            return names;
        }
        String name = body.substring(0, eq);
        Flag flag = Flags.get(name);
        Collection<String> values;
        if (flag != null) {
            values = Flags.offers(mode, flag) ? flag.values(mode) : List.of();
        } else {
            StateKey<?> rule = mode.type().supportedRules().get(name);
            values = rule != null && rule.initial() instanceof Boolean ? List.of("true", "false") : List.of();
        }
        return values.stream().map(v -> "-" + name + "=" + v).toList();
    }

    /** The request as the requester typed it, for the messages both sides read. */
    public @NotNull String describe() {
        return words.isEmpty() ? "" : " " + String.join(" ", words);
    }
}
