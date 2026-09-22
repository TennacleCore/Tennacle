package tennacle.games.bridge;


import tennacle.games.Match;
import tennacle.games.Matches;

import io.github.term4.canvas.presets.HypixelCard;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.flow.Bundle;
import io.github.term4.grotto.modules.outcome.Results;
import io.github.term4.grotto.modules.outcome.Score;
import io.github.term4.grotto.modules.view.Summary;
import io.github.term4.grotto.state.Scope;

/** Bridge's end screens, one per network. */
final class Ends {

    private Ends() {}

    // the full scrims layout; damage/pressure/blocks/hits are filler until their mechanics land
    static Summary scrims() {
        return Matches.summary()
                .row("Score", (g, side, th, color) -> th.meter(
                        g.state().get(Scope.team(side.getFirst().team()), Bridge.GOALS),
                        g.rules().resolve(Scope.team(side.getFirst().team()), Score.TARGET), color))
                .row("Damage", (g, side, th, color) -> th.hearts(0))
                .row("Pressure", "The share of the game this side spent away from its own base",
                        (g, side, th, color) -> th.value(Match.pressure(g, side.getFirst().team())))
                .row("Blocks Placed", (g, side, th, color) -> th.value(0))
                .row("Blocks Broken", (g, side, th, color) -> th.value(0))
                .cardSection(
                        (g, seat, th, side) -> th.prose("Goals: ")
                                .append(th.meter(g.state().get(Scope.team(seat.team()), Bridge.GOALS),
                                        g.rules().resolve(Scope.team(seat.team()), Score.TARGET), side)),
                        (g, seat, th, side) -> th.prose("Damage: ").append(th.hearts(0)),
                        (g, seat, th, side) -> th.prose("KDR: ").append(th.value(0))
                                .append(th.dim(" | ")).append(th.value(0)).append(th.dim(" (0.0)")))
                .cardSection(
                        (g, seat, th, side) -> th.prose("Blocks Placed: ").append(th.value(0)),
                        (g, seat, th, side) -> th.prose("Blocks Broken: ").append(th.value(0)))
                .cardSection(
                        (g, seat, th, side) -> th.prose("Hits: ").append(th.value(0))
                                .append(th.dim(" | ")).append(th.value(0)),
                        (g, seat, th, side) -> th.prose("Hits Blocked: ").append(th.value("0%")),
                        (g, seat, th, side) -> th.prose("Arrows: ").append(th.value("0/0"))
                                .append(th.dim(" (0%)")));
    }

    // the scrims card on the house palette, and a name click opens the frozen record (minemen's screen)
    static GameModule tennacle() {
        Results records = Results.of().quiet(); // first: the snapshot precedes the card, and cleanup
        return Bundle.of(records, scrims().click((g, name) -> records.viewCommand(name)));
    }

    // captured rows: Goals, Damage Dealt, accuracies, gapples, blocks - fillers until their mechanics land
    static GameModule hypixel() {
        Results records = Results.of().quiet(); // the name opens the frozen record, as the house card does
        return Bundle.of(records, Matches.summary().hypixel("Bridge Duel")
                        .row("Goals", (g, side, th, c) -> th.value(
                                g.state().get(Scope.team(side.getFirst().team()), Bridge.GOALS)))
                        .row("Damage Dealt", (g, side, th, c) -> th.hearts(0))
                        .row("Melee Accuracy", (g, side, th, c) -> th.value("0%"))
                        .row("Bow Accuracy", (g, side, th, c) -> th.value("0%"))
                        .row("Golden Apples Eaten", (g, side, th, c) -> th.value(0))
                        .row("Blocks Placed", (g, side, th, c) -> th.value(0))
                        .after((g, me) -> HypixelCard.playAgain(
                                Summary.won(g, me), "/duel " + Match.opponentName(g, me) + " bridge -preset=hypixel"))
                .click((g, name) -> records.viewCommand(name)));
    }

    static Results minemen() {
        return Matches.results();
    }
}
