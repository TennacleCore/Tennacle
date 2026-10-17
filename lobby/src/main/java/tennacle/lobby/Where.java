package tennacle.lobby;

import io.github.term4.grotto.Game;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import tennacle.games.Matches;
import tennacle.games.Mode;

/** Where a friend is, as their row in {@code /fl} reads it: "is in the Duels Lobby", "is in a Bridge Game". */
final class Where {

    private Where() {}

    static @NotNull String of(@NotNull Player player) {
        Game game = Game.of(player);
        if (game != null) return "is in " + a(title(game)) + " Game";
        Game watched = Matches.grotto().spectating(player);
        if (watched != null) return "is watching " + a(title(watched)) + " Game";
        Lobbies.Lobby lobby = Lobbies.in(player);
        if (lobby != null) return "is in the " + (lobby.name().endsWith("Lobby") ? lobby.name() : lobby.name() + " Lobby");
        return "is online";
    }

    private static String title(Game game) {
        Mode mode = Matches.modeOf(game);
        return mode != null ? mode.title() : "Custom";
    }

    private static String a(String noun) {
        boolean vowel = !noun.isEmpty() && "aeiou".indexOf(Character.toLowerCase(noun.charAt(0))) >= 0;
        return (vowel ? "an " : "a ") + noun;
    }
}
