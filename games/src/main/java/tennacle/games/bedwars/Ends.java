package tennacle.games.bedwars;

import tennacle.games.Cards;
import tennacle.games.Matches;

import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.state.Scope;

/** Bed Wars' end: the captured block, the winning side over the top three killers. */
final class Ends {

    private Ends() {}

    static GameModule hypixel() {
        return Cards.ends(Matches.summary().hypixelGame("Bed Wars")
                .top("Killer", 3, (g, seat) -> g.state().get(Scope.slot(seat.index()), BedWars.KILLS)));
    }
}
