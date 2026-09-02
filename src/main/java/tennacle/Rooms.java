package tennacle;

import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Parties;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.canvas.chat.Chat;
import io.github.term4.grotto.Game;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The rooms: a member speaks to their game (spectators included), a spectator to the other spectators of
 * that game, anyone else to the players in the same shard tree, else the same instance. {@code /p} is the
 * party's channel. The routing itself is Canvas's {@link Chat}.
 */
public final class Rooms {

    private Rooms() {}

    public static void install() {
        Chat.install(Rooms::of);
        Chat.channel("p", "party", NamedTextColor.LIGHT_PURPLE, Parties::of, "you're not in a party", "pc");
    }

    private static Chat.@Nullable Room of(@NotNull Player me, @NotNull String message) {
        Game game = gameOf(me);
        if (game != null) {
            List<Player> to = new ArrayList<>();
            game.forEachAudience(to::add);
            return new Chat.Room(to, Chat.named(me, Match.teamColor(game, me), message));
        }
        Game watched = Matches.grotto().spectating(me);
        if (watched != null) return new Chat.Room(watched.spectators(), Chat.tagged("spec", me, message));
        Object room = roomOf(me);
        List<Player> to = new ArrayList<>();
        for (Player other : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            if (gameOf(other) == null && Matches.grotto().spectating(other) == null && roomOf(other) == room) to.add(other);
        }
        return new Chat.Room(to, Chat.plain(me, message));
    }

    // ghosts are unbound: the tag can't route them, their seat still can
    private static @Nullable Game gameOf(Player player) {
        Game game = Game.of(player);
        if (game != null) return game;
        for (Game candidate : Matches.grotto().games()) {
            if (candidate.isGhost(player)) return candidate;
        }
        return null;
    }

    /** The shard tree's root, else the instance: the players who share it share a room. */
    private static Object roomOf(Player player) {
        Shard shard = Shard.of(player);
        if (shard == null) return player.getInstance();
        while (shard.parent() != null) shard = shard.parent();
        return shard;
    }
}
