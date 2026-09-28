package tennacle.games.map;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.block.Block;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Builds that take ticks: a pop-up tower going up two cells a tick, a bridge trailing an egg. Each is a {@link Job}
 * advanced once a tick while the game has any; the cells themselves go through {@link Building}, so what a job
 * lays is a member's placement like any other.
 */
public final class Structures implements GameModule {

    private static final String TICK = "structures:tick";

    /** One build in progress; {@code false} once it has nothing more to do. */
    @FunctionalInterface
    public interface Job {
        boolean tick();
    }

    public record Cell(@NotNull Point pos, @NotNull Block block) {}

    private final List<Job> jobs = new ArrayList<>();
    private Game game;

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of();
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        game.state().alarms().register(TICK, (tick, payload) -> run());
    }

    @Override
    public void onEnd(@NotNull Game game, @NotNull Outcome outcome) {
        jobs.clear();
    }

    /** Starts {@code job} from the next tick. */
    public void add(@NotNull Job job) {
        jobs.add(job);
        if (jobs.size() == 1) game.state().alarms().arm(TICK, game.tick() + 1);
    }

    private void run() {
        jobs.removeIf(job -> !job.tick());
        if (!jobs.isEmpty()) game.state().alarms().arm(TICK, game.tick() + 1);
    }

    /** {@code cells} in order, {@code perTick} a tick; a cell {@code place} declines still takes its slot. */
    public static @NotNull Job lay(@NotNull List<Cell> cells, int perTick, @NotNull Predicate<Cell> place) {
        return new Job() {
            private int next;

            @Override
            public boolean tick() {
                for (int i = 0; i < perTick && next < cells.size(); i++) place.test(cells.get(next++));
                return next < cells.size();
            }
        };
    }
}
