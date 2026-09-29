package tennacle.games.bridge;


import tennacle.games.Cards;
import tennacle.games.Matches;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.outcome.Results;
import io.github.term4.grotto.modules.outcome.Score;
import io.github.term4.grotto.modules.view.Summary;
import io.github.term4.grotto.state.Scope;

/** Bridge's end screens, one per network. */
final class Ends {

    private Ends() {}

    static Summary scrims() {
        return Cards.scrims("Score", null,
                        (g, side, th, color) -> th.meter(goals(g, side.getFirst().team()), target(g, side.getFirst().team()), color),
                        (g, seat, th, side) -> th.prose("Goals: ").append(th.meter(goals(g, seat.team()), target(g, seat.team()), side)),
                        Bridge.KILLS, Bridge.DEATHS)
                .cardSection(Cards.BLOCKS_PLACED_LINE, Cards.BLOCKS_BROKEN_LINE)
                .cardSection(Cards.HITS_LINE, Cards.HITS_BLOCKED_LINE, Cards.ARROWS_LINE);
    }

    // the scrims card on the house palette (minemen's screen)
    static GameModule tennacle() {
        return Cards.ends(scrims());
    }

    // captured rows; the gapples stay a filler until eating is counted
    static GameModule hypixel() {
        return Cards.ends(Matches.summary().hypixel("Bridge Duel")
                .row("Goals", (g, side, th, c) -> th.value(goals(g, side.getFirst().team())))
                .row("Damage Dealt", Cards.DAMAGE)
                .row("Melee Accuracy", Cards.MELEE_ACCURACY)
                .row("Bow Accuracy", Cards.BOW_ACCURACY)
                .row("Golden Apples Eaten", (g, side, th, c) -> th.value(0))
                .row("Blocks Placed", Cards.BLOCKS_PLACED)
                .after(Cards.playAgain("bridge -preset=hypixel")));
    }

    private static int goals(Game game, String team) {
        return game.state().get(Scope.team(team), Bridge.GOALS);
    }

    private static int target(Game game, String team) {
        return game.rules().resolve(Scope.team(team), Score.TARGET);
    }

    static Results minemen() {
        return Matches.results();
    }
}
