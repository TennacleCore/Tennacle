package tennacle.games.sumo;

import net.kyori.adventure.text.format.NamedTextColor;

import tennacle.games.Match;
import tennacle.games.Matches;

import io.github.term4.canvas.presets.HypixelCard;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.flow.Bundle;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.outcome.Results;
import io.github.term4.grotto.modules.view.Summary;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;

/** Sumo's end screens, one per network. */
final class Ends {

    private Ends() {}

    // captured hypixel sumo end block + the play-again line
    static GameModule hypixel() {
        Results records = Results.of().quiet(); // the name opens the frozen record, as the house card does
        return Bundle.of(records, Matches.summary().hypixel("Sumo Duel")
                        .row("Melee Accuracy", (g, side, th, c) -> th.value(accuracy(g, side.getFirst())))
                        .after((g, me) -> HypixelCard.playAgain(
                                Summary.won(g, me), "/duel " + Match.opponentName(g, me) + " sumo"))
                .click((g, name) -> records.viewCommand(name)));
    }

    static Results minemen() {
        return Matches.results();
    }

    static Summary scrims() {
        return Matches.summary()
                .sideColors(NamedTextColor.WHITE, NamedTextColor.WHITE) // a plain duel has no sides to color
                .row("Melee Accuracy", (g, side, th, c) -> th.value(accuracy(g, side.getFirst())));
    }

    // the one stat on the house palette, and a name click opens the frozen record
    static GameModule tennacle() {
        Results records = Results.of().quiet(); // first: the snapshot precedes the card, and cleanup
        return Bundle.of(records, scrims().click((g, name) -> records.viewCommand(name)));
    }


    private static String accuracy(Game game, Slot seat) {
        int swings = game.state().get(Scope.slot(seat.index()), Sumo.SWINGS);
        int landed = game.state().get(Scope.slot(seat.index()), Sumo.LANDED);
        return swings > 0 ? Math.round(landed * 100.0 / swings) + "%" : "N/A";
    }
}
