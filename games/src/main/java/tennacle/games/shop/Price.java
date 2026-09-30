package tennacle.games.shop;

import java.util.List;
import net.minestom.server.entity.Player;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.grotto.Game;
import net.minestom.server.codec.Codec;
import org.jetbrains.annotations.NotNull;

/** An amount of a currency; as a rule it reads {@code 4 iron}. */
public record Price(@NotNull Currency currency, int amount) {

    public static final Codec<Price> CODEC = Codec.STRING.transform(Price::parse, Price::toString);

    public static @NotNull Price of(int amount, @NotNull Currency currency) {
        return new Price(currency, amount);
    }

    public static @NotNull Price parse(@NotNull String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length != 2) throw new IllegalArgumentException("a price reads '<amount> <currency>', not '" + text + "'");
        Currency currency = Currency.of(parts[1]);
        if (currency == null) throw new IllegalArgumentException("no currency '" + parts[1] + "'");
        int amount = Integer.parseInt(parts[0]);
        if (amount < 0) throw new IllegalArgumentException("a price is never below zero");
        return new Price(currency, amount);
    }

    @Override
    public @NotNull String toString() {
        return amount + " " + currency.id();
    }

    /** The prices {@code key} sets for {@code player}, a tier each; a rule emptied by hand reads as its default. */
    public static @NotNull List<Price> ladder(@NotNull Game game, @NotNull Player player, @NotNull StateKey<List<Price>> key) {
        List<Price> set = game.rules().resolve(player, key);
        return set.isEmpty() ? key.initial() : set;
    }
}
