package tennacle.games.shop;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.gear.Kits;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.function.BiFunction;

/** Items handed over through the seat's kit under a role, so they land in the buyer's slot for it; refused when they do not fit. */
public final class Goods implements Offer {

    private final String id;
    private final StateKey<Price> price;
    private final @Nullable String role;
    private final BiFunction<Game, Player, ItemStack> stack;

    private Goods(String id, StateKey<Price> price, @Nullable String role, BiFunction<Game, Player, ItemStack> stack) {
        this.id = id;
        this.price = price;
        this.role = role;
        this.stack = stack;
    }

    /** {@code stack} for {@code price} by default, the rule {@code shop.<id>}. */
    public static @NotNull Goods of(@NotNull String id, @NotNull Price price, @Nullable String role,
                                    @NotNull BiFunction<Game, Player, ItemStack> stack) {
        return new Goods(id, rule(id, price), role, stack);
    }

    /** The rule {@code shop.<id>}. */
    public static @NotNull StateKey<Price> rule(@NotNull String id, @NotNull Price price) {
        return StateKey.of("shop." + id, Price.CODEC, price);
    }

    public @NotNull StateKey<Price> rule() {
        return price;
    }

    public @NotNull ItemStack stack(@NotNull Game game, @NotNull Player buyer) {
        return stack.apply(game, buyer);
    }

    @Override
    public @NotNull String id() {
        return id;
    }

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(price);
    }

    @Override
    public @NotNull Price price(@NotNull Game game, @NotNull Player buyer) {
        return game.rules().resolve(buyer, price);
    }

    @Override
    public @Nullable Refusal refusal(@NotNull Game game, @NotNull Player buyer) {
        return kits(game).fits(buyer, role, stack(game, buyer)) ? null : Refusal.FULL;
    }

    @Override
    public void grant(@NotNull Game game, @NotNull Player buyer) {
        kits(game).give(buyer, role, stack(game, buyer));
    }

    @Override
    public void grant(@NotNull Game game, @NotNull Player buyer, int into) {
        kits(game).giveAt(buyer, into, stack(game, buyer));
    }

    static Kits kits(Game game) {
        Kits kits = game.module(Kits.class);
        if (kits == null) throw new IllegalStateException("a shop's goods need Kits installed");
        return kits;
    }
}
