package tennacle.games.boxing;

import tennacle.Msg;
import tennacle.games.Match;
import tennacle.looks.Frames;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.outcome.Score;
import io.github.term4.grotto.modules.view.Scoreboard;
import io.github.term4.grotto.modules.flow.TimeLimit;
import io.github.term4.grotto.state.Scope;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.entity.Player;

/** Boxing's board rows per network; the chrome comes from {@link Frames}. */
final class Boards {

    private Boards() {}

    // the minemen board look: the hit diff block, first-to line, combo
    static Scoreboard minemen() {
        return Frames.minemen("Boxing", b -> b
                .line((g, me) -> Component.text("Hits: ", NamedTextColor.WHITE)
                        .append(Component.text("(" + signed(Boxing.hits(g, Match.viewed(g, me)) - Boxing.hits(g, Match.opponent(g, Match.viewed(g, me)))) + ")",
                                NamedTextColor.GREEN)), Boxing.HITS)
                .line((g, me) -> Component.text("  You: ", NamedTextColor.WHITE)
                        .append(Component.text(Boxing.hits(g, Match.viewed(g, me)), NamedTextColor.LIGHT_PURPLE)), Boxing.HITS)
                .line((g, me) -> Component.text("  Them: ", NamedTextColor.WHITE)
                        .append(Component.text(Boxing.hits(g, Match.opponent(g, Match.viewed(g, me))), NamedTextColor.LIGHT_PURPLE)), Boxing.HITS)
                .line((g, me) -> Component.text("  1st to " + target(g, me) + "!",
                        NamedTextColor.GRAY), Score.TARGET)
                .blank()
                .line((g, me) -> Frames.minemenRow("Combo: ", Boxing.seatStat(g, Match.viewed(g, me), Boxing.COMBO)), Boxing.COMBO));
    }

    // the hypixel duels frame with boxing content
    static Scoreboard hypixel() {
        return Frames.hypixel("BOXING", "Boxing Duel",
                g -> TimeLimit.left(g), b -> b
                .line((g, me) -> Frames.hypixelRow("Hits: ", Boxing.hits(g, Match.viewed(g, me))), Boxing.HITS)
                .line((g, me) -> Frames.hypixelRow("Combo: ", Boxing.seatStat(g, Match.viewed(g, me), Boxing.COMBO)), Boxing.COMBO));
    }

    // the scrims frame with boxing content
    static Scoreboard scrims() {
        return Frames.scrims("Boxing", Boards::scrimsRows);
    }

    // the house board is scrims' rows in the house frame
    static Scoreboard tennacle() {
        return Frames.tennacle("Boxing", Boards::scrimsRows);
    }

    private static void scrimsRows(Scoreboard b) {
        b.line((g, me) -> Component.text("Hits: ", NamedTextColor.WHITE)
                        .append(Msg.SCRIMS.value(Boxing.hits(g, Match.viewed(g, me))))
                        .append(Msg.SCRIMS.prose(" | "))
                        .append(Msg.SCRIMS.value(Boxing.hits(g, Match.opponent(g, Match.viewed(g, me))))), Boxing.HITS)
                .line((g, me) -> Component.text("Combo: ", NamedTextColor.WHITE)
                        .append(Msg.SCRIMS.value(Boxing.seatStat(g, Match.viewed(g, me), Boxing.COMBO))), Boxing.COMBO);
    }

    // a handicap is scoped, so the target reads at the seat being watched, not at the game
    private static int target(Game game, Player viewer) {
        Player viewed = Match.viewed(game, viewer);
        return viewed != null ? game.rules().resolve(viewed, Score.TARGET)
                : game.rules().resolve(Scope.GAME, Score.TARGET);
    }

    private static String signed(int diff) {
        return diff > 0 ? "+" + diff : String.valueOf(diff);
    }
}
