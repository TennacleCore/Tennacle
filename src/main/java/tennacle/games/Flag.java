package tennacle.games;

import io.github.term4.grotto.state.StateKey;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * A word of a request's tail, by name: {@code -time=10}, {@code -cages=off}, {@code -look=scrims}. What it lands
 * as is its own business - one rule, several, a look, a map, or code at launch - so what a player types is the
 * app's vocabulary, and a rule id is one thing a flag may reach for. Modes and modules register theirs through
 * {@link Flags}.
 */
public interface Flag {

    @NotNull String name();

    /** Whether the mode has what this flag sets; a flag over a rule the mode lacks is not offered there. */
    default boolean appliesTo(@NotNull Mode mode) {
        return true;
    }

    /** The values worth offering after the equals sign, when the set is small enough to name. */
    default @NotNull Collection<String> values(@NotNull Mode mode) {
        return List.of();
    }

    /** Lands {@code value} in the options being built; a value it cannot read throws with the reason. */
    void apply(@NotNull Mode mode, @NotNull String value, @NotNull Options.Builder into);

    /** A flag that sets one rule, offered wherever the mode carries the rule. */
    static <T> @NotNull Flag rule(@NotNull String name, @NotNull StateKey<T> key, @NotNull Function<String, T> parse,
                                  @NotNull Collection<String> values) {
        return new Flag() {
            @Override public @NotNull String name() { return name; }
            @Override public boolean appliesTo(@NotNull Mode mode) { return mode.type().supportedRules().containsKey(key.id()); }
            @Override public @NotNull Collection<String> values(@NotNull Mode mode) { return values; }
            @Override public void apply(@NotNull Mode mode, @NotNull String value, @NotNull Options.Builder into) {
                T parsed;
                try {
                    parsed = parse.apply(value);
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("-" + name + "=" + value + ": " + e.getMessage());
                }
                into.rule(key, parsed);
            }
        };
    }

    /** {@link #rule} over a boolean, spoken as on and off. */
    static @NotNull Flag onOff(@NotNull String name, @NotNull StateKey<Boolean> key) {
        return rule(name, key, value -> switch (value.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes" -> true;
            case "off", "false", "no" -> false;
            default -> throw new IllegalArgumentException("on or off");
        }, List.of("on", "off"));
    }

    /** {@code flag}, offered only in the modes named - the same rule can mean different things to different games. */
    static @NotNull Flag in(@NotNull Flag flag, @NotNull String... modes) {
        Set<String> only = Set.of(modes);
        return new Flag() {
            @Override public @NotNull String name() { return flag.name(); }
            @Override public boolean appliesTo(@NotNull Mode mode) { return only.contains(mode.name()) && flag.appliesTo(mode); }
            @Override public @NotNull Collection<String> values(@NotNull Mode mode) { return flag.values(mode); }
            @Override public void apply(@NotNull Mode mode, @NotNull String value, @NotNull Options.Builder into) { flag.apply(mode, value, into); }
        };
    }
}
