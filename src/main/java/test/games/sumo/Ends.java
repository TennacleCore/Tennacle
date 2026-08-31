package test.games.sumo;

import test.games.Duels;

import io.github.term4.canvas.theme.HypixelCard;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.Results;
import io.github.term4.grotto.modules.Summary;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import net.minestom.server.entity.Player;

/** Sumo's end screens, one per network. */
final class Ends {

    private Ends() {}

    // captured hypixel sumo end block + the play-again line
    static Summary hypixel() {
        return Duels.summary().hypixel("Sumo Duel")
                .row("Melee Accuracy", (g, side, th, c) -> th.value(accuracy(g, side.getFirst())))
                .after((g, me) -> HypixelCard.playAgain(
                        Summary.won(g, me), "/duel " + opponentName(g, me) + " sumo"));
    }

    static Results minemen() {
        return Duels.results();
    }

    static Summary scrims() {
        return Duels.summary()
                .row("Melee Accuracy", (g, side, th, c) -> th.value(accuracy(g, side.getFirst())));
    }

    private static String opponentName(Game game, Player me) {
        Player them = Duels.opponent(game, me);
        return them != null ? them.getUsername() : "";
    }

    private static String accuracy(Game game, Slot seat) {
        int swings = game.state().get(Scope.slot(seat.index()), Sumo.SWINGS);
        int landed = game.state().get(Scope.slot(seat.index()), Sumo.LANDED);
        return swings > 0 ? Math.round(landed * 100.0 / swings) + "%" : "N/A";
    }
}
