package tennacle.games.practice;

import java.util.random.RandomGenerator;

/**
 * The bot's connection: a round trip drawn each tick from the normal range, or held at a spike's value while one
 * lasts. Each way is half the trip, rounded to whole ticks at random by the remainder, and never overtakes what
 * went before it: the stream is ordered.
 */
final class Lag {

    private static final double TICK_MS = 50;

    private final RandomGenerator random;
    private int ping, spikeLeft, spikePing;
    private long lastDown, lastUp;

    Lag(RandomGenerator random) {
        this.random = random;
    }

    void tick(Drill drill) {
        if (spikeLeft > 0) {
            spikeLeft--;
            ping = spikePing;
            return;
        }
        double perTick = drill.get(Drill.Knob.SPIKE_CHANCE) / 100.0 / 20;
        if (perTick > 0 && random.nextDouble() < perTick) {
            spikePing = range(drill.get(Drill.Knob.SPIKE_MIN), drill.get(Drill.Knob.SPIKE_MAX));
            spikeLeft = drill.get(Drill.Knob.SPIKE_TICKS) - 1;
            ping = spikePing;
            return;
        }
        ping = range(drill.get(Drill.Knob.PING_MIN), drill.get(Drill.Knob.PING_MAX));
    }

    int ping() {
        return ping;
    }

    /** The tick something sent toward the bot at {@code now} reaches it. */
    long down(long now) {
        lastDown = Math.max(lastDown, now + oneWay());
        return lastDown;
    }

    /** The tick something the bot sends at {@code now} reaches the server. */
    long up(long now) {
        lastUp = Math.max(lastUp, now + oneWay());
        return lastUp;
    }

    int expectedUp() {
        return (int) Math.round(ping / 2.0 / TICK_MS);
    }

    void clear() {
        lastDown = 0;
        lastUp = 0;
    }

    private int oneWay() {
        double ticks = ping / 2.0 / TICK_MS;
        int whole = (int) ticks;
        return whole + (random.nextDouble() < ticks - whole ? 1 : 0);
    }

    private int range(int low, int high) {
        return low + (high > low ? random.nextInt(high - low + 1) : 0);
    }
}
