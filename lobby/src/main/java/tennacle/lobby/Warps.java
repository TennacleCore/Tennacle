package tennacle.lobby;

import io.github.term4.grotto.Game;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.games.LobbyCommands;
import tennacle.games.Matches;
import tennacle.games.matchmaking.PartyExit;
import tennacle.games.watch.SpectateCommands;
import tennacle.social.party.Parties;

import java.util.List;

/**
 * Where {@code /party warp} takes a member: the leader's lobby, a seat in the game the leader waits in, else a
 * view of the game the leader plays or watches. A member playing elsewhere leaves that game on the way.
 */
final class Warps implements Parties.Warp {

    @Override
    public boolean busy(@NotNull Player member, @NotNull Player leader) {
        Game game = LobbyCommands.seatedIn(member);
        return game != null && game != place(leader);
    }

    @Override
    public boolean here(@NotNull Player member, @NotNull Player leader) {
        Game game = place(leader);
        if (game != null) return place(member) == game;
        return place(member) == null && member.getInstance() == leader.getInstance();
    }

    @Override
    public @Nullable String bring(@NotNull Player member, @NotNull Player leader) {
        Game game = place(leader);
        Lobbies.Lobby lobby = Lobbies.in(leader);
        if (game == null && lobby == null) return "they can't follow you there.";
        PartyExit.leaveTogether(List.of(member));
        if (game == null) {
            Lobbies.send(member, lobby);
            return null;
        }
        if (Matches.enter(game, List.of(member))) return null; // a room still filling seats them
        String refused = SpectateCommands.watch(member, game);
        if (refused != null) Matches.toLobby(member);
        return refused;
    }

    // the game a player is part of: seated, haunting, or watching
    private static @Nullable Game place(Player player) {
        Game game = LobbyCommands.seatedIn(player);
        return game != null ? game : Matches.grotto().spectating(player);
    }
}
