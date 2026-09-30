package tennacle.games;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.codec.Codec;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.function.LongConsumer;
import java.util.function.ToLongFunction;

/** One go a cooldown, the last go stamped at the seat under a ledger key so a replay branch refuses alike; a seatless player never goes. */
public final class Throttle {

    private static final long NEVER = Long.MIN_VALUE / 4;

    private final Game game;
    private final StateKey<Long> stamp;
    private final ToLongFunction<Player> cooldown;

    public Throttle(@NotNull Game game, @NotNull StateKey<Long> stamp, @NotNull ToLongFunction<Player> cooldown) {
        this.game = game;
        this.stamp = stamp;
        this.cooldown = cooldown;
    }

    /** The cooldown is {@code rule}'s, at the player. */
    public static @NotNull Throttle of(@NotNull Game game, @NotNull StateKey<Long> stamp, @NotNull StateKey<Long> rule) {
        return new Throttle(game, stamp, user -> game.rules().resolve(user, rule));
    }

    public static @NotNull Throttle fixed(@NotNull Game game, @NotNull StateKey<Long> stamp, long ticks) {
        return new Throttle(game, stamp, user -> ticks);
    }

    /** A stamp key that never stands in the way of a first go. */
    public static @NotNull StateKey<Long> stamp(@NotNull String id) {
        return StateKey.of(id, Codec.LONG, NEVER, true);
    }

    public long cooldown(@NotNull Player user) {
        return cooldown.applyAsLong(user);
    }

    /** Ticks until {@code user} may go again, 0 when they may now; -1 with no seat. */
    public long left(@NotNull Player user) {
        Slot seat = game.roster().slotOf(user);
        if (seat == null) return -1;
        long since = game.tick() - game.state().get(Scope.slot(seat.index()), stamp);
        return Math.max(0, cooldown.applyAsLong(user) - since);
    }

    /** Stamps the clock when {@code user} may go now; else {@code refused} hears the ticks left. */
    public boolean pass(@NotNull Player user, @NotNull LongConsumer refused) {
        long left = left(user);
        if (left < 0) return false;
        if (left > 0) {
            refused.accept(left);
            return false;
        }
        game.state().set(Scope.slot(game.roster().slotOf(user).index()), stamp, game.tick());
        return true;
    }
}
