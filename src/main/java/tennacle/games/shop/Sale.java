package tennacle.games.shop;

import org.jetbrains.annotations.NotNull;

/** How a {@link Shop#buy} went. */
public sealed interface Sale {

    record Sold(@NotNull Offer offer, @NotNull Price price) implements Sale {}

    record Refused(@NotNull Offer offer, @NotNull Refusal refusal) implements Sale {}

    /** Not enough held: {@code missing} more of the price's currency. */
    record Short(@NotNull Offer offer, @NotNull Price price, int missing) implements Sale {}
}
