package test.games.boxing;

import test.Msg;
import test.looks.Frames;

import io.github.term4.grotto.modules.Scoreboard;
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
                        .append(Component.text("(" + signed(Boxing.hits(g, me) - Boxing.hits(g, Boxing.opponent(g, me))) + ")",
                                NamedTextColor.GREEN)), Boxing.HITS)
                .line((g, me) -> Component.text("  You: ", NamedTextColor.WHITE)
                        .append(Component.text(Boxing.hits(g, me), NamedTextColor.LIGHT_PURPLE)), Boxing.HITS)
                .line((g, me) -> Component.text("  Them: ", NamedTextColor.WHITE)
                        .append(Component.text(Boxing.hits(g, Boxing.opponent(g, me)), NamedTextColor.LIGHT_PURPLE)), Boxing.HITS)
                .line((g, me) -> Component.text("  1st to " + g.rules().resolve(Scope.GAME, Boxing.TARGET) + "!",
                        NamedTextColor.GRAY))
                .blank()
                .line((g, me) -> Frames.minemenRow("Combo: ", Boxing.seatStat(g, me, Boxing.COMBO)), Boxing.COMBO));
    }

    // the hypixel duels frame with boxing content
    static Scoreboard hypixel() {
        return Frames.hypixel("BOXING", "Boxing Duel",
                g -> g.rules().resolve(Scope.GAME, Boxing.LIMIT) - g.tick(), b -> b
                .line((g, me) -> Frames.hypixelRow("Hits: ", Boxing.hits(g, me)), Boxing.HITS)
                .line((g, me) -> Frames.hypixelRow("Combo: ", Boxing.seatStat(g, me, Boxing.COMBO)), Boxing.COMBO));
    }

    // the scrims frame with boxing content
    static Scoreboard scrims() {
        return Frames.scrims("Boxing", b -> b
                .line((g, me) -> Component.text("Hits: ", NamedTextColor.WHITE)
                        .append(Msg.SCRIMS.value(Boxing.hits(g, me)))
                        .append(Msg.SCRIMS.prose(" | "))
                        .append(Msg.SCRIMS.value(Boxing.hits(g, Boxing.opponent(g, me)))), Boxing.HITS)
                .line((g, me) -> Component.text("Combo: ", NamedTextColor.WHITE)
                        .append(Msg.SCRIMS.value(Boxing.seatStat(g, me, Boxing.COMBO))), Boxing.COMBO));
    }

    private static String signed(int diff) {
        return diff > 0 ? "+" + diff : String.valueOf(diff);
    }
}
