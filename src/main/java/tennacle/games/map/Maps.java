package tennacle.games.map;

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

    /** The name {@code arena} goes by here - that instance, or a reload of the same map; null = not this pool's. */
    default @Nullable String nameOf(@NotNull Arena arena) {
        String sameMap = null;
        for (String name : names()) {
            Arena candidate = named(name);
            if (candidate == arena) return name;
            if (candidate != null && candidate.meta().id().equals(arena.meta().id())) sameMap = name;
        }
        return sameMap;
    }
}
