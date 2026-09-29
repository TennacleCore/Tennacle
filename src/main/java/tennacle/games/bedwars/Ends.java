package tennacle.games.bedwars;

import tennacle.games.Matches;

import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.flow.Bundle;
import io.github.term4.grotto.modules.outcome.Results;
import io.github.term4.grotto.state.Scope;

/** Bed Wars' end: the captured block, the winning side over the top three killers. */
final class Ends {

    private Ends() {}

    static GameModule hypixel() {
        Results records = Results.of().quiet(); // first: the snapshot precedes the block, and cleanup
        return Bundle.of(records, Matches.summary().hypixelGame("Bed Wars")
                .top("Killer", 3, (g, seat) -> g.state().get(Scope.slot(seat.index()), BedWars.KILLS))
                .click((g, name) -> records.viewCommand(name))); // a name opens the frozen record, as the duels' cards do
    }
}
