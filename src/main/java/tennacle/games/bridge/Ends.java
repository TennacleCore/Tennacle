package tennacle.games.bridge;

import tennacle.games.Duels;

import io.github.term4.canvas.theme.HypixelCard;
import io.github.term4.grotto.modules.Results;
import io.github.term4.grotto.modules.Summary;
import io.github.term4.grotto.state.Scope;

/** Bridge's end screens, one per network. */
final class Ends {

    private Ends() {}

    // the full scrims layout; damage/pressure/blocks/hits are filler until their mechanics land
    static Summary scrims() {
        return Duels.summary()
                .row("Score", (g, side, th, color) -> th.meter(
                        g.state().get(Scope.team(side.getFirst().team()), Bridge.GOALS),
                        g.rules().resolve(Scope.GAME, Bridge.TARGET), color))
                .row("Damage", (g, side, th, color) -> th.hearts(0))
                .row("Pressure", "Time spent with the majority of players on the enemy's side",
                        (g, side, th, color) -> th.value("0%"))
                .row("Blocks Placed", (g, side, th, color) -> th.value(0))
                .row("Blocks Broken", (g, side, th, color) -> th.value(0))
                .cardSection(
                        (g, seat, th, side) -> th.prose("Goals: ")
                                .append(th.meter(g.state().get(Scope.team(seat.team()), Bridge.GOALS),
                                        g.rules().resolve(Scope.GAME, Bridge.TARGET), side)),
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

    // captured rows: Goals, Damage Dealt, accuracies, gapples, blocks - fillers until their mechanics land
    static Summary hypixel() {
        return Duels.summary().hypixel("Bridge Duel")
                .row("Goals", (g, side, th, c) -> th.value(
                        g.state().get(Scope.team(side.getFirst().team()), Bridge.GOALS)))
                .row("Damage Dealt", (g, side, th, c) -> th.hearts(0))
                .row("Melee Accuracy", (g, side, th, c) -> th.value("0%"))
                .row("Bow Accuracy", (g, side, th, c) -> th.value("0%"))
                .row("Golden Apples Eaten", (g, side, th, c) -> th.value(0))
                .row("Blocks Placed", (g, side, th, c) -> th.value(0))
                .after((g, me) -> HypixelCard.playAgain(
                        Summary.won(g, me), "/duel " + Duels.opponentName(g, me) + " bridge hypixel"));
    }

    static Results minemen() {
        return Duels.results();
    }
}
