package tennacle.games.shop;

import net.kyori.adventure.text.format.TextColor;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** What a price is paid in. Registered by id, so a price read from a rule names it. */
public interface Currency {

    @NotNull String id();

    int held(@NotNull Player player);

    /** Takes {@code amount}; the caller made sure it is held. */
    void take(@NotNull Player player, int amount);

    /** What {@code amount} of it is called: {@code Emerald} for one, {@code Emeralds} for more. */
    @NotNull String name(int amount);

    @NotNull TextColor color();

    static <C extends Currency> @NotNull C register(@NotNull C currency) {
        Registry.BY_ID.put(currency.id(), currency);
        return currency;
    }

    static @Nullable Currency of(@NotNull String id) {
        return Registry.BY_ID.get(id);
    }

    final class Registry {
        private static final Map<String, Currency> BY_ID = new ConcurrentHashMap<>();

        private Registry() {}
    }
}
