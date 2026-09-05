package tennacle.games;

import io.github.term4.grotto.state.StateKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The registered {@link Flag}s, and which of them each mode offers. Registering defines a flag; {@link #offer}
 * puts it on a mode's menu, in the order the mode lists them. The app defines one: {@code preset}, the copied
 * version of a game - its rules and its look together, by platform name. A mode offers none until it lists them.
 */
public final class Flags {

    private static final Map<String, Flag> flags = new ConcurrentHashMap<>();
    private static final Map<String, List<Flag>> offered = new ConcurrentHashMap<>();

    private Flags() {}

    static {
        register(new Flag() {
            @Override public @NotNull String name() { return "preset"; }
            @Override public @NotNull String description() { return "the copied version of a game: its rules and its look together"; }
            @Override public @NotNull Collection<String> values(@NotNull Mode mode) { return Matches.platforms(); }
            @Override public void apply(@NotNull Mode mode, @NotNull String value, @NotNull Options.Builder into) {
                if (Matches.platform(value) == null) {
                    throw new IllegalArgumentException("no preset named " + value + " (" + String.join(", ", Matches.platforms()) + ")");
                }
                into.platform(value);
            }
        });
    }

    public static void register(@NotNull Flag flag) {
        flags.put(flag.name(), flag);
    }

    public static @Nullable Flag get(@NotNull String name) {
        return flags.get(name);
    }

    /**
     * Puts registered flags on {@code mode}'s menu, in this order. A flag over a rule the mode does not carry is
     * refused here, at boot, rather than accepted and ignored at a request.
     */
    public static void offer(@NotNull Mode mode, @NotNull String... names) {
        List<Flag> menu = new ArrayList<>(offered.getOrDefault(mode.name(), List.of()));
        for (String name : names) {
            Flag flag = flags.get(name);
            if (flag == null) throw new IllegalStateException(mode.name() + " offers -" + name + ", which no one registered");
            StateKey<?> rule = flag.requires();
            if (rule != null && !mode.type().supportedRules().containsKey(rule.id())) {
                throw new IllegalStateException(mode.name() + " offers -" + name + " over " + rule.id() + ", a rule it does not carry");
            }
            menu.removeIf(f -> f.name().equals(name));
            menu.add(flag);
        }
        offered.put(mode.name(), List.copyOf(menu));
    }

    /** The flags {@code mode} offers, in the order it listed them. */
    public static @NotNull List<Flag> of(@NotNull Mode mode) {
        return offered.getOrDefault(mode.name(), List.of());
    }

    /** Whether {@code flag} is on {@code mode}'s menu. */
    public static boolean offers(@NotNull Mode mode, @NotNull Flag flag) {
        return of(mode).contains(flag);
    }
}
