package tennacle.games.bedfight;

import tennacle.games.Matches;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.flow.Bundle;
import io.github.term4.grotto.modules.outcome.Results;
import io.github.term4.grotto.modules.view.Summary;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;

import java.util.List;
import java.util.Locale;

/** Bed fight's end screens. The captures for hypixel and minemen are still to come; they read the house card. */
final class Ends {

    private Ends() {}

    static Summary scrims() {
        return Matches.summary()
                .row("Beds Broken", (g, side, th, color) -> th.value(total(g, side, BedFight.BEDS)))
                .row("Kills", (g, side, th, color) -> th.value(total(g, side, BedFight.KILLS)))
                .row("Deaths", (g, side, th, color) -> th.value(total(g, side, BedFight.DEATHS)))
                .row("Bed", "Whether the side still had one when the game ended", (g, side, th, color) ->
                        Beds.stands(g, side.getFirst().team()) ? th.value("Standing") : th.dim("Broken"))
                .cardSection(
                        (g, seat, th, side) -> th.prose("Beds Broken: ").append(th.value(stat(g, seat, BedFight.BEDS))),
                        (g, seat, th, side) -> th.prose("KDR: ").append(th.value(stat(g, seat, BedFight.KILLS)))
                                .append(th.dim(" | ")).append(th.value(stat(g, seat, BedFight.DEATHS)))
                                .append(th.dim(" (" + kdr(g, seat) + ")")));
    }

    // the scrims card on the house palette, and a name click opens the frozen record (minemen's screen)
    static GameModule tennacle() {
        Results records = Results.of().quiet(); // first: the snapshot precedes the card, and cleanup
        return Bundle.of(records, scrims().click((g, name) -> records.viewCommand(name)));
    }

    private static String kdr(Game game, Slot seat) {
        int deaths = stat(game, seat, BedFight.DEATHS);
        return String.format(Locale.ROOT, "%.1f", stat(game, seat, BedFight.KILLS) / (double) Math.max(1, deaths));
    }

    private static int stat(Game game, Slot seat, StateKey<Integer> key) {
        return game.state().get(Scope.slot(seat.index()), key);
    }

    /** A side's own: bed fight seats a whole party, so a row is the sum of it. */
    private static int total(Game game, List<Slot> side, StateKey<Integer> key) {
        int sum = 0;
        for (Slot seat : side) sum += stat(game, seat, key);
        return sum;
    }
}
