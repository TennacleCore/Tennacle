package tennacle.games;

import io.github.term4.barnacle.Arena;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.function.Supplier;

/** Where a mode plays: the next map ({@link #get}, the pool's own pick), one by name, and the names on offer. */
public interface Maps extends Supplier<Arena> {

    @Nullable Arena named(@NotNull String name);

    @NotNull Set<String> names();
}
