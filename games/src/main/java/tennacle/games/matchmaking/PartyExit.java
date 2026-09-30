package tennacle.games.matchmaking;

import tennacle.Msg;
import tennacle.games.Match;
import tennacle.games.Matches;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

import tennacle.party.Parties;
/** A party waits together: one member out of a filling game takes the rest out with them. */
public final class PartyExit implements GameModule {

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of();
    }

    @Override
    public void install(@NotNull Game game) {}

    @Override
    public void onLeave(@NotNull Game game, @NotNull Player player, @NotNull Game.Departure departure) {
        if (departure.seat() == null || !Match.filling(game)) return;
        for (Player mate : Parties.of(player)) {
            if (mate == player || game.roster().slotOf(mate) == null) continue;
            game.depart(mate);
            Msg.say(mate, "Your party left the game.");
            Matches.toLobby(mate);
        }
    }
}
