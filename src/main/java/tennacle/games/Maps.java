package tennacle.games;

import io.github.term4.barnacle.Arena;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Where a mode plays: the next map ({@link #get}, the pool's own pick), one by name, and the names on offer.
 * {@link #label} is what a request calls the pool's own pick - the rotation by name, the way a network names
 * its queues (Random, Random Competitive) - so a mode with several pools one day labels each.
 */
public interface Maps extends Supplier<Arena> {

    @Nullable Arena named(@NotNull String name);

    @NotNull Set<String> names();

    default @NotNull String label() {
        return "Random";
    }
}
