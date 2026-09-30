package tennacle.games.shop;

import org.jetbrains.annotations.NotNull;

/** Why a sale did not happen, whatever the buyer holds: a reason the storefront words. */
public record Refusal(@NotNull String reason) {

    public static final Refusal FULL = new Refusal("full");
    public static final Refusal OWNED = new Refusal("owned");
    /** They already have better. */
    public static final Refusal BEYOND = new Refusal("beyond");
}
