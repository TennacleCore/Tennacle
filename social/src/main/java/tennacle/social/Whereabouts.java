package tennacle.social;

import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.function.Function;

/** What a friend's row says after their name. The module that knows the lobbies and games sets it. */
public final class Whereabouts {

    private static volatile Function<Player, String> rule = player -> "is online";

    private Whereabouts() {}

    /** A phrase that follows the name: "is in the Duels Lobby". */
    public static void use(@NotNull Function<Player, String> where) {
        rule = where;
    }

    public static @NotNull String of(@NotNull Player player) {
        return rule.apply(player);
    }
}
