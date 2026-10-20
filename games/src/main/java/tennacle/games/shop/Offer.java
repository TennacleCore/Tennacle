package tennacle.games.shop;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/** One thing a {@link Shop} sells. */
public interface Offer {

    /** What the stats count a purchase as: {@code items-bought}, {@code upgrades-bought}. */
    default @NotNull String kind() {
        return "items";
    }

    @NotNull String id();

    /** This purchase in the stats ({@code bought-<this>}), read before the grant. */
    default @NotNull String bought(@NotNull Game game, @NotNull Player buyer) {
        return id();
    }

    /** The rules it reads, its price among them. */
    default @NotNull Set<StateKey<?>> rules() {
        return Set.of();
    }

    @NotNull Price price(@NotNull Game game, @NotNull Player buyer);

    /** Why {@code buyer} cannot have it now, whatever they hold; null when nothing stands in the way. */
    @Nullable Refusal refusal(@NotNull Game game, @NotNull Player buyer);

    /** Hands it over before it is paid for; false when it could not, and the buyer is not charged. */
    boolean grant(@NotNull Game game, @NotNull Player buyer);

    /** {@link #grant} into inventory slot {@code into} when what it hands over goes there. */
    default boolean grant(@NotNull Game game, @NotNull Player buyer, int into) {
        return grant(game, buyer);
    }
}
