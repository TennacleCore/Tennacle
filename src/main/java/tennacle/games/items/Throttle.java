package tennacle.games.items;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One use of an item a cooldown per member (captured: red {@code Please wait 0.5s to use that again}); a refusal costs nothing. */
final class Throttle {

    private final Game game;
    private final StateKey<Long> cooldown;
    private final Map<Player, Long> last = new ConcurrentHashMap<>();

    Throttle(@NotNull Game game, @NotNull StateKey<Long> cooldown) {
        this.game = game;
        this.cooldown = cooldown;
    }

    /** Whether {@code user} may use it now, telling them when not. */
    boolean allow(@NotNull Player user) {
        if (game.roster().slotOf(user) == null) return false;
        long ticks = game.rules().resolve(user, cooldown);
        Long at = last.get(user);
        if (at != null && game.tick() - at < ticks) {
            user.sendMessage(ItemLines.of(game).wait(game, ticks));
            return false;
        }
        last.put(user, game.tick());
        return true;
    }

    void clear() {
        last.clear();
    }
}
