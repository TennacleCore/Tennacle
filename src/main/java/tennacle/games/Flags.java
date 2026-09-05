package tennacle.games;

import io.github.term4.grotto.modules.flow.TimeLimit;
import io.github.term4.grotto.modules.space.Daylight;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.presets.Preset;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The registered {@link Flag}s, and which of them each mode offers. Registering defines a flag; {@link #offer}
 * puts it on a mode's menu, in the order the mode lists them. The five below exist for any mode to offer.
 */
public final class Flags {

    private static final Map<String, Flag> flags = new ConcurrentHashMap<>();
    private static final Map<String, List<Flag>> offered = new ConcurrentHashMap<>();

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
        // a time of day by name, or the ticks themselves; the sky then holds there for the match
        Map<String, Long> daylight = Map.of("day", 1000L, "noon", 6000L, "sunset", 12000L, "night", 13000L, "midnight", 18000L);
        register(Flag.rule("daylight", Daylight.AT, value -> {
            Long named = daylight.get(value.toLowerCase(Locale.ROOT));
            if (named != null) return named;
            try {
                long ticks = Long.parseLong(value);
                if (ticks < 0 || ticks >= 24000) throw new IllegalArgumentException("0 to 23999");
                return ticks;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("day, noon, sunset, night, midnight, or ticks");
            }
        }, List.of("day", "noon", "sunset", "night", "midnight")));
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
