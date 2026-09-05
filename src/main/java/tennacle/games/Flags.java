package tennacle.games;

import io.github.term4.grotto.modules.flow.TimeLimit;
import io.github.term4.polyp.presets.Preset;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The registered {@link Flag}s: the five every mode has, and whatever a mode or module adds at boot. */
public final class Flags {

    private static final Map<String, Flag> flags = new ConcurrentHashMap<>();

    private Flags() {}

    static {
        register(new Flag() {
            @Override public @NotNull String name() { return "look"; }
            @Override public @NotNull Collection<String> values(@NotNull Mode mode) { return Matches.lookNames(); }
            @Override public void apply(@NotNull Mode mode, @NotNull String value, @NotNull Options.Builder into) {
                if (!Matches.lookNames().contains(value)) {
                    throw new IllegalArgumentException("no look named " + value + " (" + String.join(", ", Matches.lookNames()) + ")");
                }
                into.look(value);
            }
        });
        register(new Flag() {
            @Override public @NotNull String name() { return "platform"; }
            @Override public @NotNull Collection<String> values(@NotNull Mode mode) { return Matches.platforms(); }
            @Override public void apply(@NotNull Mode mode, @NotNull String value, @NotNull Options.Builder into) {
                if (Matches.platform(value) == null) {
                    throw new IllegalArgumentException("no platform named " + value + " (" + String.join(", ", Matches.platforms()) + ")");
                }
                into.platform(value);
            }
        });
        register(new Flag() {
            @Override public @NotNull String name() { return "map"; }
            @Override public @NotNull Collection<String> values(@NotNull Mode mode) { return mode.arena().names(); }
            @Override public void apply(@NotNull Mode mode, @NotNull String value, @NotNull Options.Builder into) {
                into.map(mode, value);
            }
        });
        // minutes, or m:ss - a clock is what a player thinks in, ticks are what the rule holds
        register(Flag.rule("time", TimeLimit.LIMIT, Flags::clockTicks, List.of()));
        List<String> presets = Arrays.stream(Preset.values()).map(p -> p.name().toLowerCase(Locale.ROOT)).toList();
        register(Flag.rule("preset", Presets.KEY, value -> {
            if (!presets.contains(value.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("one of " + presets);
            return value.toLowerCase(Locale.ROOT);
        }, presets));
    }

    public static void register(@NotNull Flag flag) {
        flags.put(flag.name(), flag);
    }

    public static @Nullable Flag get(@NotNull String name) {
        return flags.get(name);
    }

    /** The flags that apply to {@code mode}, by name. */
    public static @NotNull List<Flag> of(@NotNull Mode mode) {
        return flags.values().stream().filter(f -> f.appliesTo(mode))
                .sorted((a, b) -> a.name().compareTo(b.name())).toList();
    }

    static long clockTicks(String value) {
        long seconds;
        try {
            int colon = value.indexOf(':');
            seconds = colon < 0 ? Long.parseLong(value) * 60
                    : Long.parseLong(value.substring(0, colon)) * 60 + Long.parseLong(value.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("minutes, or m:ss");
        }
        if (seconds <= 0) throw new IllegalArgumentException("a clock has to run");
        return seconds * 20;
    }
}
