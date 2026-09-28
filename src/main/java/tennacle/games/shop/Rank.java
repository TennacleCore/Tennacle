package tennacle.games.shop;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * A level on a seat's or a team's ladder: an armor tier, a pickaxe's, the forge's. A purchase only writes the
 * level; whatever reads the ladder (the kit's armor, the forge) takes it from there.
 */
public final class Rank implements Offer {

    /** Whose ladder a level is on. */
    public enum Holder { SEAT, TEAM }

    private final String id;
    private final StateKey<Integer> ladder;
    private final Holder holder;
    private final int level;
    private final @Nullable StateKey<Price> price;
    private final @Nullable StateKey<List<Price>> prices;

    private Rank(String id, StateKey<Integer> ladder, Holder holder, int level,
                 @Nullable StateKey<Price> price, @Nullable StateKey<List<Price>> prices) {
        this.id = id;
        this.ladder = ladder;
        this.holder = holder;
        this.level = level;
        this.price = price;
        this.prices = prices;
    }

    /** Exactly {@code level}, bought over any lower one: refused at it, or above it. */
    public static @NotNull Rank at(@NotNull String id, @NotNull StateKey<Integer> ladder, @NotNull Holder holder,
                                   int level, @NotNull Price price) {
        return new Rank(id, ladder, holder, level, Goods.rule(id, price), null);
    }

    /** The level after the holder's, one price a level (the rule {@code shop.<id>}); refused at the top. */
    public static @NotNull Rank next(@NotNull String id, @NotNull StateKey<Integer> ladder, @NotNull Holder holder,
                                     @NotNull List<Price> prices) {
        return new Rank(id, ladder, holder, -1, null, StateKey.of("shop." + id, Price.CODEC.list(), List.copyOf(prices)));
    }

    public @NotNull StateKey<Integer> ladder() {
        return ladder;
    }

    /** The price rule: one {@link Price} for {@link #at}, a list for {@link #next}. */
    public @NotNull StateKey<?> rule() {
        return price != null ? price : prices;
    }

    /** The holder's level now; {@code 0} for a buyer with no seat. */
    public int current(@NotNull Game game, @NotNull Player buyer) {
        Scope scope = scope(game, buyer);
        return scope != null ? game.state().get(scope, ladder) : 0;
    }

    /** The highest level sold. */
    public int top(@NotNull Game game, @NotNull Player buyer) {
        return prices != null ? game.rules().resolve(buyer, prices).size() : level;
    }

    /** The level a purchase would give now; at the top, the top. */
    public int selling(@NotNull Game game, @NotNull Player buyer) {
        return prices != null ? Math.min(current(game, buyer) + 1, top(game, buyer)) : level;
    }

    @Override
    public @NotNull String id() {
        return id;
    }

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(rule());
    }

    @Override
    public @NotNull Price price(@NotNull Game game, @NotNull Player buyer) {
        if (price != null) return game.rules().resolve(buyer, price);
        List<Price> each = game.rules().resolve(buyer, prices);
        return each.get(Math.max(0, Math.min(selling(game, buyer), each.size()) - 1));
    }

    @Override
    public @Nullable Refusal refusal(@NotNull Game game, @NotNull Player buyer) {
        int now = current(game, buyer);
        if (prices != null) return now >= top(game, buyer) ? Refusal.OWNED : null;
        return now == level ? Refusal.OWNED : now > level ? Refusal.BEYOND : null;
    }

    @Override
    public void grant(@NotNull Game game, @NotNull Player buyer) {
        Scope scope = scope(game, buyer);
        if (scope != null) game.state().set(scope, ladder, selling(game, buyer));
    }

    private @Nullable Scope scope(Game game, Player buyer) {
        Slot seat = game.roster().slotOf(buyer);
        if (seat == null) return null;
        return holder == Holder.SEAT ? Scope.slot(seat.index()) : Scope.team(seat.team());
    }
}
