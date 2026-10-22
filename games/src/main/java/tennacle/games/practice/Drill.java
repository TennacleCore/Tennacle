package tennacle.games.practice;

import io.github.term4.grotto.vault.VaultKey;
import net.minestom.server.codec.Codec;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import tennacle.PlayerPrefs;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.random.RandomGenerator;

/**
 * What the bot does, a player's own and kept: one int per {@link Knob}. Reach is in quarter blocks, eyes to hitbox;
 * waits and reduce ticks are ticks; latency is the round trip in milliseconds.
 */
public final class Drill {

    public enum Knob {
        HIT_TYPE("type", 0, 0, 3, 1),
        SPRINT_CHANCE("sprintpct", 50, 0, 100, 10),
        COMBO_GAP("combo", 15, 1, 40, 1),
        COMBO_SPRINT("cosprint", 20, 0, 100, 5),
        KNOCKBACK("kb", 0, 0, 5, 1),
        REACH_MIN("rmin", 8, 4, 16, 1),
        REACH_MAX("rmax", 8, 4, 16, 1),
        REACH_LEAN("rlean", 0, 0, 3, 1),
        CONTINUOUS("cont", 0, 0, 1, 1),
        HITS("hits", 1, 1, 10, 1),
        WAIT_MIN("wmin", 0, 0, 20, 1),
        WAIT_MAX("wmax", 0, 0, 20, 1),
        WAIT_LEAN("wlean", 0, 0, 3, 1),
        HIT_SELECT("select", 0, 0, 1, 1),
        PATIENCE("patience", 40, 5, 100, 5),
        COUNTER("counter", 0, 0, 1, 1),
        COUNTER_MIN("cmin", 0, 0, 10, 1),
        COUNTER_MAX("cmax", 0, 0, 10, 1),
        COUNTER_LEAN("clean", 0, 0, 3, 1),
        REDUCE("reduce", 0, 0, 1, 1),
        REDUCE_START_MIN("rsmin", 0, 0, 10, 1),
        REDUCE_START_MAX("rsmax", 0, 0, 10, 1),
        REDUCE_START_LEAN("rslean", 0, 0, 3, 1),
        REDUCE_PATTERN("rpattern", 0, 0, 2, 1),
        REDUCE_CPS("rcps", 10, 1, 20, 1),
        REDUCE_TICKS("rticks", 0b11, 0, (1 << Drill.PATTERN_TICKS) - 1, 1),
        REDUCE_FOR("rfor", 0, 0, 20, 1),
        REDUCE_MISS("rmiss", 0, 0, 100, 5),
        JUMP_RESET("jump", 0, 0, 100, 10),
        CHAIN_GAP("chain", 10, 1, 40, 1),
        CHAIN_MISS("chmiss", 10, 0, 50, 5),
        CHAIN_CPS("chcps", 2, 0, 10, 1),
        CHAIN_DELAY("chdelay", 1, 0, 5, 1),
        MISS_SWING("mswing", 10, 0, 50, 5),
        CPS_SWING("cswing", 2, 0, 10, 1),
        PING_MIN("pmin", 0, 0, 500, 10),
        PING_MAX("pmax", 0, 0, 500, 10),
        SPIKE_CHANCE("spike", 0, 0, 100, 5),
        SPIKE_MIN("smin", 100, 0, 1000, 25),
        SPIKE_MAX("smax", 100, 0, 1000, 25),
        SPIKE_TICKS("sticks", 10, 1, 40, 1),
        STRAFE("strafe", 50, 0, 100, 10),
        STRAFE_MIN("stmin", 8, 2, 40, 1),
        STRAFE_MAX("stmax", 20, 2, 40, 1),
        SPACING("space", 1, 0, 1, 1),
        KEEP("keep", 10, 4, 16, 1),
        TURN("turn", 90, 10, 180, 10),
        JITTER("jitter", 2, 0, 10, 1),
        BOT_SPEED("bspeed", 0, 0, 3, 1),
        SPEED("speed", 0, 0, 3, 1),
        SLOWNESS("slow", 0, 0, 3, 1);

        final String id;
        final int def, min, max, step;

        Knob(String id, int def, int min, int max, int step) {
            this.id = id;
            this.def = def;
            this.min = min;
            this.max = max;
            this.step = step;
        }
    }

    public enum HitType { SPRINT, NORMAL, RANDOM, SPACING }

    public enum Pattern { EVERY_TICK, PER_SECOND, CUSTOM }

    /** How a random pick between a low and a high falls. */
    public enum Lean {
        EVEN, LOW, HIGH, MIDDLE;

        double sample(RandomGenerator random) {
            double u = random.nextDouble();
            return switch (this) {
                case EVEN -> u;
                case LOW -> u * u;
                case HIGH -> 1 - (1 - u) * (1 - u);
                case MIDDLE -> (u + random.nextDouble()) / 2;
            };
        }
    }

    static final int PATTERN_TICKS = 12;
    static final int NEVER = Knob.PATIENCE.max;

    // a range's ends: moving one past the other drags it along
    private static final Map<Knob, Knob> LOW_OF = Map.of(Knob.REACH_MAX, Knob.REACH_MIN, Knob.WAIT_MAX, Knob.WAIT_MIN,
            Knob.COUNTER_MAX, Knob.COUNTER_MIN, Knob.REDUCE_START_MAX, Knob.REDUCE_START_MIN, Knob.PING_MAX, Knob.PING_MIN,
            Knob.SPIKE_MAX, Knob.SPIKE_MIN, Knob.STRAFE_MAX, Knob.STRAFE_MIN);
    private static final Map<Knob, Knob> HIGH_OF = new HashMap<>();

    static {
        LOW_OF.forEach((high, low) -> HIGH_OF.put(low, high));
    }

    public static final Drill DEFAULT = new Drill(new EnumMap<>(Knob.class));

    private static PlayerPrefs.Entry<String, Drill> store;

    private final EnumMap<Knob, Integer> values;

    private Drill(EnumMap<Knob, Integer> values) {
        this.values = values;
    }

    // before the first login
    static void register() {
        store = PlayerPrefs.register(VaultKey.of("practice:drill", Codec.STRING, ""), Drill::decode, Drill::encode);
    }

    public static @NotNull Drill of(@NotNull Player player) {
        Drill drill = store != null ? store.of(player.getUuid()) : null;
        return drill != null ? drill : DEFAULT;
    }

    static void set(@NotNull Player player, @NotNull Drill drill) {
        if (store != null) store.set(player, drill);
    }

    public int get(@NotNull Knob knob) {
        return values.getOrDefault(knob, knob.def);
    }

    boolean on(Knob knob) {
        return get(knob) != 0;
    }

    Drill with(Knob knob, int value) {
        EnumMap<Knob, Integer> next = new EnumMap<>(values);
        int v = Math.max(knob.min, Math.min(knob.max, value));
        next.put(knob, v);
        Knob low = LOW_OF.get(knob), high = HIGH_OF.get(knob);
        if (low != null && get(low) > v) next.put(low, v);
        if (high != null && get(high) < v) next.put(high, v);
        return new Drill(next);
    }

    Drill step(Knob knob, boolean up) {
        return with(knob, get(knob) + (up ? knob.step : -knob.step));
    }

    HitType hitType() {
        return HitType.values()[get(Knob.HIT_TYPE)];
    }

    Pattern pattern() {
        return Pattern.values()[get(Knob.REDUCE_PATTERN)];
    }

    Lean lean(Knob knob) {
        return Lean.values()[get(knob)];
    }

    double reach(RandomGenerator random) {
        return pick(Knob.REACH_MIN, Knob.REACH_MAX, Knob.REACH_LEAN, random) / 4.0;
    }

    int ticks(Knob low, Knob high, Knob lean, RandomGenerator random) {
        return (int) Math.round(pick(low, high, lean, random));
    }

    private double pick(Knob low, Knob high, Knob lean, RandomGenerator random) {
        return get(low) + lean(lean).sample(random) * (get(high) - get(low));
    }

    static String encode(Drill d) {
        StringJoiner out = new StringJoiner(";");
        d.values.forEach((k, v) -> out.add(k.id + "=" + v));
        return out.toString();
    }

    // an unknown or bad field keeps its default
    static Drill decode(String text) {
        Map<String, Knob> byId = new HashMap<>();
        for (Knob k : Knob.values()) byId.put(k.id, k);
        EnumMap<Knob, Integer> values = new EnumMap<>(Knob.class);
        for (String part : text.split(";")) {
            int eq = part.indexOf('=');
            Knob k = eq > 0 ? byId.get(part.substring(0, eq)) : null;
            if (k == null) continue;
            try {
                values.put(k, Math.max(k.min, Math.min(k.max, Integer.parseInt(part.substring(eq + 1)))));
            } catch (NumberFormatException ignored) {
            }
        }
        return new Drill(values);
    }
}
