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

    /** The maps this pool holds out of rotation, each with what it still wants; a builder's map goes missing here. */
    default @NotNull java.util.Map<String, String> holdouts() {
        return java.util.Map.of();
    }

    /** What a map of this name is held out until it has; null = it is in the rotation, or nothing goes by it. */
    default @Nullable String wanting(@NotNull String name) {
        return holdouts().get(name);
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
