package tennacle.games.lives;

import io.github.term4.grotto.GameModule;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/** What keeps a side respawning, a bed or a wither: once it falls, the side's next death is its last. */
public interface Anchor extends GameModule {

    boolean stands(@NotNull String team);

    /** Runs with the side's team id as its anchor falls. */
    @NotNull Anchor onFall(@NotNull Consumer<String> action);

    /** Whoever brought the side's anchor down, credited a final death nobody else is owed; null for none. */
    @Nullable Player breaker(@NotNull String team);

    /** A death as decided, before the mode's own death actions. */
    default void died(@NotNull Lives.Death death) {}
}
