package tennacle.games;

import tennacle.games.map.Maps;
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
 * word is the map, from the mode's pool. A {@code -name=value} word is a {@link Flag} the mode offers, by name
 * ({@code -name} alone passes {@code true}), and nothing else is a word here: a rule changes in the game, through
 * {@code /rules}, never from a request. What a flag sets - a platform, rules, a map, code at launch - lands at
 * launch, the rules over the platform's ruleset as the game's first live modifier, so {@code /rules} layers over
 * them the way it layers over anything. Everything is checked here, so a bad word answers the requester rather
 * than failing the launch the accepter triggers.
 */
public record Options(@Nullable String map, @Nullable String platform,
                      @NotNull Ruleset rules, @NotNull List<Consumer<Game>> atLaunch, @NotNull List<String> words) {

    public static final Options NONE = new Options(null, null, Ruleset.named("flags"), List.of(), List.of());

    /** What a flag lands in. */
    public static final class Builder {
        private final Mode mode;
        private String map, platform;
        private Ruleset rules = Ruleset.named("flags");
        private final List<Consumer<Game>> atLaunch = new ArrayList<>();

        Builder(Mode mode) {
            this.mode = mode;
        }

        public @NotNull Builder map(@NotNull Mode mode, @NotNull String name) {
            if (mode.arena().named(name) == null) {
                throw new IllegalArgumentException("No map named " + name + " for " + mode.title()
                        + ". Maps: " + String.join(", ", mode.arena().names()) + ".");
            }
            this.map = name;
            return this;
        }

        public @NotNull Builder platform(@NotNull String name) { this.platform = name; return this; }
        public <T> @NotNull Builder rule(@NotNull StateKey<T> key, @NotNull T value) { rules = rules.rule(key, value); return this; }
        /** Code that runs on the launched game, after its ruleset is folded - what no rule expresses. */
        public @NotNull Builder atLaunch(@NotNull Consumer<Game> hook) { atLaunch.add(hook); return this; }

        Options build(List<String> words) {
            List<String> problems = rules.validate(Mechanics.polyp.profiles().global(), mode.type());
            if (!problems.isEmpty()) throw new IllegalArgumentException(String.join("; ", problems));
            return new Options(map, platform, rules, List.copyOf(atLaunch), List.copyOf(words));
        }
    }

    public static @NotNull Options parse(@NotNull Mode mode, @NotNull List<String> words) {
        Builder into = new Builder(mode);
        for (String word : words) {
            if (!word.startsWith("-")) {
                if (into.map != null) throw new IllegalArgumentException("One map, not " + into.map + " and " + word + ".");
                into.map(mode, word);
                continue;
            }
            String body = word.substring(1);
            int eq = body.indexOf('=');
            String name = eq < 0 ? body : body.substring(0, eq);
            String value = eq < 0 ? "true" : body.substring(eq + 1);
            if (name.isEmpty()) throw new IllegalArgumentException("A flag needs a name: " + word + ".");
            Flag flag = Flags.get(name);
            if (flag == null || !Flags.offers(mode, flag)) {
                throw new IllegalArgumentException("-" + name + " is not a flag of " + mode.title() + ". " + menu(mode));
            }
            flag.apply(mode, value, into);
        }
        return into.build(words);
    }

    private static String menu(Mode mode) {
        List<Flag> flags = Flags.of(mode);
        if (flags.isEmpty()) return mode.title() + " takes no flags.";
        return mode.title() + " takes " + String.join(", ", flags.stream().map(f -> "-" + f.name()).toList()) + ".";
    }

    /** What the tail can hold at {@code token}: map names bare, the mode's flags after a dash, a flag's values after its equals sign. */
    public static @NotNull Collection<String> completions(@NotNull Mode mode, @NotNull String token) {
        if (!token.startsWith("-")) return mode.arena().names();
        String body = token.substring(1);
        int eq = body.indexOf('=');
        if (eq < 0) return Flags.of(mode).stream().map(f -> "-" + f.name()).toList();
        String name = body.substring(0, eq);
        Flag flag = Flags.get(name);
        if (flag == null || !Flags.offers(mode, flag)) return List.of();
        return flag.values(mode).stream().map(v -> "-" + name + "=" + v).toList();
    }

    /** The request as the requester typed it, for the messages both sides read. */
    public @NotNull String describe() {
        return words.isEmpty() ? "" : " " + String.join(" ", words);
    }

    /** The flag words alone, as typed - the request's flag line. */
    public @NotNull List<String> flags() {
        return words.stream().filter(w -> w.startsWith("-")).toList();
    }
}
