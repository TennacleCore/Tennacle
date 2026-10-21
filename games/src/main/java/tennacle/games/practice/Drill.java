package tennacle.games.practice;

import io.github.term4.grotto.vault.VaultKey;
import net.minestom.server.codec.Codec;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import tennacle.PlayerPrefs;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * What the bot does, a player's own and kept. Reach is measured eyes to hitbox, as a client's; delays and latency
 * are ticks. {@code hits} 0 fights on until stopped.
 */
public record Drill(boolean sprint, int knockback, double reachMin, double reachMax, int botSpeed, int speed,
                    int slowness, int hits, int delayMin, int delayMax, int latency) {

    public static final Drill DEFAULT = new Drill(true, 0, 2.0, 2.0, 0, 0, 0, 1, 0, 0, 0);

    static final double REACH_LOW = 1.0, REACH_HIGH = 4.0, REACH_STEP = 0.25;
    static final int MAX_KNOCKBACK = 5, MAX_LEVEL = 3, MAX_HITS = 10, MAX_DELAY = 20, MAX_LATENCY = 10;

    private static PlayerPrefs.Entry<String, Drill> store;

    // before the first login
    static void register() {
        store = PlayerPrefs.register(VaultKey.of("practice:drill", Codec.STRING, ""), Drill::decode, Drill::encode);
    }

    public static @NotNull Drill of(@NotNull Player player) {
        Drill drill = store.of(player.getUuid());
        return drill != null ? drill : DEFAULT;
    }

    static void set(@NotNull Player player, @NotNull Drill drill) {
        store.set(player, drill);
    }

    boolean endless() {
        return hits == 0;
    }

    double reach(RandomGenerator random) {
        return reachMin + random.nextDouble() * (reachMax - reachMin);
    }

    int delay(RandomGenerator random) {
        return delayMin + random.nextInt(delayMax - delayMin + 1);
    }

    Drill withSprint(boolean v) { return new Drill(v, knockback, reachMin, reachMax, botSpeed, speed, slowness, hits, delayMin, delayMax, latency); }
    Drill withKnockback(int v) { return new Drill(sprint, v, reachMin, reachMax, botSpeed, speed, slowness, hits, delayMin, delayMax, latency); }
    Drill withBotSpeed(int v) { return new Drill(sprint, knockback, reachMin, reachMax, v, speed, slowness, hits, delayMin, delayMax, latency); }
    Drill withSpeed(int v) { return new Drill(sprint, knockback, reachMin, reachMax, botSpeed, v, slowness, hits, delayMin, delayMax, latency); }
    Drill withSlowness(int v) { return new Drill(sprint, knockback, reachMin, reachMax, botSpeed, speed, v, hits, delayMin, delayMax, latency); }
    Drill withHits(int v) { return new Drill(sprint, knockback, reachMin, reachMax, botSpeed, speed, slowness, v, delayMin, delayMax, latency); }
    Drill withLatency(int v) { return new Drill(sprint, knockback, reachMin, reachMax, botSpeed, speed, slowness, hits, delayMin, delayMax, v); }

    // the other end follows, so the range never inverts
    Drill withReachMin(double v) { return new Drill(sprint, knockback, v, Math.max(v, reachMax), botSpeed, speed, slowness, hits, delayMin, delayMax, latency); }
    Drill withReachMax(double v) { return new Drill(sprint, knockback, Math.min(v, reachMin), v, botSpeed, speed, slowness, hits, delayMin, delayMax, latency); }
    Drill withDelayMin(int v) { return new Drill(sprint, knockback, reachMin, reachMax, botSpeed, speed, slowness, hits, v, Math.max(v, delayMax), latency); }
    Drill withDelayMax(int v) { return new Drill(sprint, knockback, reachMin, reachMax, botSpeed, speed, slowness, hits, Math.min(v, delayMin), v, latency); }

    static String encode(Drill d) {
        return String.format(Locale.ROOT, "sprint=%b;kb=%d;rmin=%.2f;rmax=%.2f;bspeed=%d;speed=%d;slow=%d;hits=%d;dmin=%d;dmax=%d;lat=%d",
                d.sprint, d.knockback, d.reachMin, d.reachMax, d.botSpeed, d.speed, d.slowness, d.hits, d.delayMin, d.delayMax, d.latency);
    }

    // a missing or bad field keeps its default
    static Drill decode(String text) {
        Map<String, String> f = new HashMap<>();
        for (String part : text.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) f.put(part.substring(0, eq), part.substring(eq + 1));
        }
        Drill d = DEFAULT;
        try {
            double rmin = clamp(num(f, "rmin", d.reachMin), REACH_LOW, REACH_HIGH);
            double rmax = clamp(num(f, "rmax", d.reachMax), rmin, REACH_HIGH);
            int dmin = (int) clamp(num(f, "dmin", d.delayMin), 0, MAX_DELAY);
            int dmax = (int) clamp(num(f, "dmax", d.delayMax), dmin, MAX_DELAY);
            return new Drill(Boolean.parseBoolean(f.getOrDefault("sprint", String.valueOf(d.sprint))),
                    (int) clamp(num(f, "kb", d.knockback), 0, MAX_KNOCKBACK), rmin, rmax,
                    (int) clamp(num(f, "bspeed", d.botSpeed), 0, MAX_LEVEL), (int) clamp(num(f, "speed", d.speed), 0, MAX_LEVEL),
                    (int) clamp(num(f, "slow", d.slowness), 0, MAX_LEVEL), (int) clamp(num(f, "hits", d.hits), 0, MAX_HITS),
                    dmin, dmax, (int) clamp(num(f, "lat", d.latency), 0, MAX_LATENCY));
        } catch (NumberFormatException e) {
            return DEFAULT;
        }
    }

    private static double num(Map<String, String> f, String key, double def) {
        String v = f.get(key);
        return v == null ? def : Double.parseDouble(v);
    }

    private static double clamp(double v, double low, double high) {
        return Math.max(low, Math.min(high, v));
    }
}
