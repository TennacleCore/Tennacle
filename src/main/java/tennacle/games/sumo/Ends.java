package tennacle.games.sumo;

import net.kyori.adventure.text.format.NamedTextColor;

import tennacle.games.Cards;
import tennacle.games.Matches;

import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.outcome.Results;
import io.github.term4.grotto.modules.view.Summary;

/** Sumo's end screens, one per network. */
final class Ends {

    private Ends() {}

    // captured hypixel sumo end block + the play-again line
    static GameModule hypixel() {
        return Cards.ends(Matches.summary().hypixel("Sumo Duel")
                .row("Melee Accuracy", Cards.MELEE_ACCURACY)
                .after(Cards.playAgain("sumo")));
    }

    static Results minemen() {
        return Matches.results();
    }

    static Summary scrims() {
        return Matches.summary()
                .sideColors(NamedTextColor.WHITE, NamedTextColor.WHITE) // a plain duel has no sides to color
                .row("Melee Accuracy", Cards.MELEE_ACCURACY);
    }

    // the one stat on the house palette
    static GameModule tennacle() {
        return Cards.ends(scrims());
    }
}
