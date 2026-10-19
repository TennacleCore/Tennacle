package tennacle.storage;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Every finished match into the {@link MatchStore}: who played, what they played, how long it ran and how it ended.
 * The roster is captured at the start because a finished game has already released its seats.
 */
public final class MatchLog {

    private static final Logger LOGGER = LoggerFactory.getLogger(MatchLog.class);

    private record Started(long at, String mode, String platform, List<List<MatchStore.Seat>> sides) {}

    private static final Map<String, Started> live = new ConcurrentHashMap<>();
    private static volatile MatchStore store;

    private MatchLog() {}

    public static void install(@NotNull MatchStore matches) {
        store = matches;
    }

    public static void started(@NotNull Game game, @NotNull String mode, @NotNull String platform,
                               @NotNull List<? extends Collection<Player>> sides) {
        if (store == null) return;
        live.put(game.id(), new Started(System.currentTimeMillis(), mode, platform, sides.stream()
                .map(side -> side.stream().map(p -> new MatchStore.Seat(p.getUuid(), p.getUsername())).toList()).toList()));
    }

    public static void ended(@NotNull Game game, @NotNull Outcome outcome) {
        Started start = live.remove(game.id());
        MatchStore matches = store;
        if (matches == null || start == null) return;
        MatchStore.Match match = new MatchStore.Match(game.id(), game.code(), start.mode(), start.platform(), outcome.kind(),
                outcome.winner(), start.at(), System.currentTimeMillis(), game.state().tick(), start.sides());
        matches.add(match).exceptionally(failed -> {
            LOGGER.error("match {} was not recorded", game.id(), failed);
            return null;
        });
    }

    public static boolean flush(long timeout, @NotNull TimeUnit unit) {
        MatchStore matches = store;
        return matches == null || matches.flush(timeout, unit);
    }
}
