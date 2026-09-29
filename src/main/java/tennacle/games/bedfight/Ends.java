package tennacle.games.bedfight;

import tennacle.games.beds.Rows;
import tennacle.games.Match;
import tennacle.games.beds.Beds;
import tennacle.games.Cards;
import tennacle.games.Matches;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.outcome.Results;
import io.github.term4.grotto.modules.view.Summary;
import io.github.term4.grotto.roster.Slot;

/** Bed fight's end screens, one per network. */
final class Ends {

    private Ends() {}

    // bridge's layout over bed fight's own
    static Summary scrims() {
        return Cards.scrims("Bed", "Whether the side still had one when the game ended",
                        (g, side, th, color) -> bed(g, side.getFirst()),
                        (g, seat, th, side) -> th.prose("Bed: ").append(bed(g, seat)),
                        BedFight.KILLS, BedFight.DEATHS)
                .cardSection(
                        (g, seat, th, side) -> th.prose("Beds Broken: ").append(th.value(Match.seatStat(g, seat, BedFight.BEDS))),
                        Cards.BLOCKS_PLACED_LINE, Cards.BLOCKS_BROKEN_LINE);
    }

    // the scrims card on the house palette (minemen's screen)
    static GameModule tennacle() {
        return Cards.ends(scrims());
    }

    // captured: the BedWars Duel card; the regeneration stays a filler until it is counted
    static GameModule hypixel() {
        return Cards.ends(Matches.summary().hypixel("BedWars Duel")
                .row("Damage Dealt", Cards.DAMAGE)
                .row("Melee Accuracy", Cards.MELEE_ACCURACY)
                .row("Blocks Placed", Cards.BLOCKS_PLACED)
                .row("Health Regenerated", (g, side, th, c) -> th.hearts(0))
                .after(Cards.playAgain("bedfight -preset=hypixel")));
    }

    static Results minemen() {
        return Matches.results();
    }

    // the board's own glyphs: a card reading Yes/No beside a scoreboard of checks read as two answers
    private static net.kyori.adventure.text.Component bed(Game game, Slot seat) {
        return Beds.stands(game, seat.team()) ? Rows.BED : Rows.GONE;
    }
}
