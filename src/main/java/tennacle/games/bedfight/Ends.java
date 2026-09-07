package tennacle.games.bedfight;

import tennacle.games.Match;
import tennacle.games.Matches;

import io.github.term4.canvas.presets.HypixelCard;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.flow.Bundle;
import io.github.term4.grotto.modules.outcome.Results;
import io.github.term4.grotto.modules.view.Summary;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;

import java.util.Locale;

/** Bed fight's end screens, one per network. */
final class Ends {

    private Ends() {}

    // bridge's layout over bed fight's own; damage/pressure/blocks are filler until their mechanics land
    static Summary scrims() {
        return Matches.summary()
                .row("Bed", "Whether the side still had one when the game ended",
                        (g, side, th, color) -> th.value(bed(g, side.getFirst())))
                .row("Damage", (g, side, th, color) -> th.hearts(0))
                .row("Pressure", "Time spent with the majority of players on the enemy's side",
                        (g, side, th, color) -> th.value("0%"))
                .row("Blocks Placed", (g, side, th, color) -> th.value(0))
                .row("Blocks Broken", (g, side, th, color) -> th.value(0))
                .cardSection(
                        (g, seat, th, side) -> th.prose("Bed: ").append(th.value(bed(g, seat))),
                        (g, seat, th, side) -> th.prose("Damage: ").append(th.hearts(0)),
                        (g, seat, th, side) -> th.prose("KDR: ").append(th.value(stat(g, seat, BedFight.KILLS)))
                                .append(th.dim(" | ")).append(th.value(stat(g, seat, BedFight.DEATHS)))
                                .append(th.dim(" (" + kdr(g, seat) + ")")))
                .cardSection(
                        (g, seat, th, side) -> th.prose("Beds Broken: ").append(th.value(stat(g, seat, BedFight.BEDS))),
                        (g, seat, th, side) -> th.prose("Blocks Placed: ").append(th.value(0)),
                        (g, seat, th, side) -> th.prose("Blocks Broken: ").append(th.value(0)));
    }

    // the scrims card on the house palette, and a name click opens the frozen record (minemen's screen)
    static GameModule tennacle() {
        Results records = Results.of().quiet(); // first: the snapshot precedes the card, and cleanup
        return Bundle.of(records, scrims().click((g, name) -> records.viewCommand(name)));
    }

    // captured: the BedWars Duel card - damage, melee accuracy, blocks placed, health regenerated
    static Summary hypixel() {
        return Matches.summary().hypixel("BedWars Duel")
                .row("Damage Dealt", (g, side, th, c) -> th.hearts(0))
                .row("Melee Accuracy", (g, side, th, c) -> th.value("0%"))
                .row("Blocks Placed", (g, side, th, c) -> th.value(0))
                .row("Health Regenerated", (g, side, th, c) -> th.hearts(0))
                .after((g, me) -> HypixelCard.playAgain(
                        Summary.won(g, me), "/duel " + Match.opponentName(g, me) + " bedfight -preset=hypixel"));
    }

    static Results minemen() {
        return Matches.results();
    }

    private static String bed(Game game, Slot seat) {
        return Beds.stands(game, seat.team()) ? "Yes" : "No";
    }

    private static String kdr(Game game, Slot seat) {
        int deaths = stat(game, seat, BedFight.DEATHS);
        return String.format(Locale.ROOT, "%.1f", stat(game, seat, BedFight.KILLS) / (double) Math.max(1, deaths));
    }

    private static int stat(Game game, Slot seat, StateKey<Integer> key) {
        return game.state().get(Scope.slot(seat.index()), key);
    }
}
