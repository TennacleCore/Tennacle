package tennacle.games.shop;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/** One thing a {@link Shop} sells. */
public interface Offer {

    @NotNull String id();

    /** The rules it reads, its price among them. */
    default @NotNull Set<StateKey<?>> rules() {
        return Set.of();
    }

    @NotNull Price price(@NotNull Game game, @NotNull Player buyer);

    /** Why {@code buyer} cannot have it now, whatever they hold; null when nothing stands in the way. */
    @Nullable Refusal refusal(@NotNull Game game, @NotNull Player buyer);

    /** Hands it over, paid for. */
    void grant(@NotNull Game game, @NotNull Player buyer);

    /** {@link #grant} into inventory slot {@code into} when what it hands over goes there. */
    default void grant(@NotNull Game game, @NotNull Player buyer, int into) {
        grant(game, buyer);
    }
}
