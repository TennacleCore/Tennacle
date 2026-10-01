package tennacle.games.matchmaking;

import tennacle.Msg;
import tennacle.games.LobbyCommands;
import tennacle.games.Match;
import tennacle.games.Matches;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.entity.Player;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import tennacle.party.Parties;
/** A party waits together: one member out of a filling game takes the rest out with them. */
public final class PartyExit implements GameModule {

    // a side moving as one routes its own members: a leave takes nobody along
    private static final Tag<Boolean> MOVING = Tag.Transient("tennacle:moving-together");

    /** Takes each of {@code side} out of the game they are in, unrouted; the ones who were in one. */
    public static @NotNull List<Player> leaveTogether(@NotNull Collection<Player> side) {
        for (Player p : side) p.setTag(MOVING, true);
        try {
            List<Player> left = new ArrayList<>();
            for (Player p : side) {
                if (LobbyCommands.leaveGame(p)) left.add(p);
            }
            return left;
        } finally {
            for (Player p : side) p.removeTag(MOVING);
        }
    }

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of();
    }

    @Override
    public void install(@NotNull Game game) {}

    @Override
    public void onLeave(@NotNull Game game, @NotNull Player player, @NotNull Game.Departure departure) {
        if (departure.seat() == null || !Match.filling(game) || player.hasTag(MOVING)) return;
        for (Player mate : Parties.of(player)) {
            if (mate == player || game.roster().slotOf(mate) == null) continue;
            game.depart(mate);
            Msg.say(mate, "Your party left the game.");
            Matches.toLobby(mate);
        }
    }
}
