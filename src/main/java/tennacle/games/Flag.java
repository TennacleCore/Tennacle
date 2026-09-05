package tennacle.games;

import io.github.term4.grotto.state.StateKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * A word of a request's tail, by name: {@code -time=10}, {@code -cages=off}, {@code -look=scrims}. What it lands
 * as is its own business - one rule, several, a look, a map, or code at launch - so what a player types is the
 * app's vocabulary, and a rule id is one thing a flag may reach for. A flag exists once it is registered with
 * {@link Flags} and is available in a mode only once that mode {@link Flags#offer offers} it: carrying the rule
 * a flag sets does not put the flag on the menu.
 */
public interface Flag {

    @NotNull String name();

    /** The rule this flag sets, when it sets exactly one - what {@link Flags#offer} checks the mode carries. */
    default @Nullable StateKey<?> requires() {
        return null;
    }

    /** The values worth offering after the equals sign, when the set is small enough to name. */
    default @NotNull Collection<String> values(@NotNull Mode mode) {
        return List.of();
    }

    /** Lands {@code value} in the options being built; a value it cannot read throws with the reason. */
    void apply(@NotNull Mode mode, @NotNull String value, @NotNull Options.Builder into);

    /** A flag that sets one rule. */
    static <T> @NotNull Flag rule(@NotNull String name, @NotNull StateKey<T> key, @NotNull Function<String, T> parse,
                                  @NotNull Collection<String> values) {
        return new Flag() {
            @Override public @NotNull String name() { return name; }
            @Override public @NotNull StateKey<?> requires() { return key; }
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
}
