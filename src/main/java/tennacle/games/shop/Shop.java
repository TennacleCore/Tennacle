package tennacle.games.shop;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a game sells: its offers by id, their prices as rules, and the one way a sale goes - refused, short of
 * the price, or paid and handed over. The screens a mode puts in front of it show the offers and word the outcome.
 */
public final class Shop implements GameModule {

    /** The buyer holds no seat, or sits out the fight dead. */
    public static final Refusal OUT = new Refusal("out");

    private final Map<String, Offer> offers = new LinkedHashMap<>();
    private Game game;

    private Shop() {}

    public static @NotNull Shop of(@NotNull Offer... offers) {
        Shop shop = new Shop();
        for (Offer offer : offers) shop.offer(offer);
        return shop;
    }

    public @NotNull Shop offer(@NotNull Offer offer) {
        if (offers.putIfAbsent(offer.id(), offer) != null) throw new IllegalArgumentException("two offers are '" + offer.id() + "'");
        return this;
    }

    public @Nullable Offer offer(@NotNull String id) {
        return offers.get(id);
    }

    public @NotNull List<Offer> offers() {
        return List.copyOf(offers.values());
    }

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        Set<StateKey<?>> out = new LinkedHashSet<>();
        for (Offer offer : offers.values()) out.addAll(offer.rules());
        return out;
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
    }

    public @NotNull Sale buy(@NotNull Player buyer, @NotNull Offer offer) {
        return buy(buyer, offer, -1);
    }

    /** {@link #buy} into inventory slot {@code into}; below zero, wherever the offer puts it. */
    public @NotNull Sale buy(@NotNull Player buyer, @NotNull Offer offer, int into) {
        if (game.roster().slotOf(buyer) == null || game.isGhost(buyer)) return new Sale.Refused(offer, OUT);
        Refusal refusal = offer.refusal(game, buyer);
        if (refusal != null) return new Sale.Refused(offer, refusal);
        Price price = offer.price(game, buyer);
        int held = price.currency().held(buyer);
        if (held < price.amount()) return new Sale.Short(offer, price, price.amount() - held);
        price.currency().take(buyer, price.amount());
        if (into >= 0) offer.grant(game, buyer, into);
        else offer.grant(game, buyer);
        return new Sale.Sold(offer, price);
    }
}
