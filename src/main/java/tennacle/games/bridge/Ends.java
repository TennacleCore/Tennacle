package tennacle.games.bridge;


import tennacle.games.Match;
import tennacle.games.Matches;

import io.github.term4.canvas.presets.HypixelCard;
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
                        g.rules().resolve(Scope.GAME, Score.TARGET), color))
                .row("Damage", (g, side, th, color) -> th.hearts(0))
                .row("Pressure", "Time spent with the majority of players on the enemy's side",
                        (g, side, th, color) -> th.value("0%"))
                .row("Blocks Placed", (g, side, th, color) -> th.value(0))
                .row("Blocks Broken", (g, side, th, color) -> th.value(0))
                .cardSection(
                        (g, seat, th, side) -> th.prose("Goals: ")
                                .append(th.meter(g.state().get(Scope.team(seat.team()), Bridge.GOALS),
                                        g.rules().resolve(Scope.GAME, Score.TARGET), side)),
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

    // the house summary: what the match tracked - goals per side, goals and kills per seat
    static Summary tennacle() {
        return Matches.summary()
                .row("Goals", (g, side, th, color) -> th.meter(
                        g.state().get(Scope.team(side.getFirst().team()), Bridge.GOALS),
                        g.rules().resolve(Scope.GAME, Score.TARGET), color))
                .cardSection(
                        (g, seat, th, side) -> th.prose("Goals: ")
                                .append(th.meter(g.state().get(Scope.team(seat.team()), Bridge.GOALS),
                                        g.rules().resolve(Scope.GAME, Score.TARGET), side)),
                        (g, seat, th, side) -> th.prose("Kills: ")
                                .append(th.value(g.state().get(Scope.slot(seat.index()), Bridge.KILLS))));
    }

    // captured rows: Goals, Damage Dealt, accuracies, gapples, blocks - fillers until their mechanics land
    static Summary hypixel() {
        return Matches.summary().hypixel("Bridge Duel")
                .row("Goals", (g, side, th, c) -> th.value(
                        g.state().get(Scope.team(side.getFirst().team()), Bridge.GOALS)))
                .row("Damage Dealt", (g, side, th, c) -> th.hearts(0))
                .row("Melee Accuracy", (g, side, th, c) -> th.value("0%"))
                .row("Bow Accuracy", (g, side, th, c) -> th.value("0%"))
                .row("Golden Apples Eaten", (g, side, th, c) -> th.value(0))
                .row("Blocks Placed", (g, side, th, c) -> th.value(0))
                .after((g, me) -> HypixelCard.playAgain(
                        Summary.won(g, me), "/duel " + Match.opponentName(g, me) + " bridge -preset=hypixel"));
    }

    static Results minemen() {
        return Matches.results();
    }
}
