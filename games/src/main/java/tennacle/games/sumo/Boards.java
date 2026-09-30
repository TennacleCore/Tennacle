package tennacle.games.sumo;

import tennacle.Msg;
import tennacle.games.Match;
import tennacle.looks.Frames;

import io.github.term4.grotto.modules.view.Scoreboard;
import io.github.term4.grotto.modules.flow.TimeLimit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.entity.Player;


/** Sumo's board rows per network; the chrome comes from {@link Frames}. */
final class Boards {

    private Boards() {}

    // the captured in-fight hypixel duels board: a bold Opponent: header with "⬆ name health❤"
    static Scoreboard hypixel() {
        return Frames.hypixel("SUMO", "Sumo Duel",
                g -> TimeLimit.left(g), b -> b
                .line(Component.text("Opponent:", NamedTextColor.WHITE, TextDecoration.BOLD))
                .line((g, me) -> {
                    Player them = Match.opponent(g, me);
                    return Component.text("⬆ ", NamedTextColor.WHITE)
                            .append(Component.text(them != null ? them.getUsername() + " " : "? ", NamedTextColor.GOLD))
                            .append(Msg.HYPIXEL.value(them != null ? (int) them.getHealth() : 0))
                            .append(Component.text("❤", NamedTextColor.RED));
                }));
    }

    // the minemen frame (no sumo capture exists - their board look, sumo content)
    static Scoreboard minemen() {
        return Frames.minemen("Sumo", b -> b
                .line((g, me) -> Frames.minemenRow("Time Left: ", Frames.timeLeft(g))));
    }

    // the scrims frame (no sumo capture exists - their board look, sumo content)
    static Scoreboard scrims() {
        return Frames.scrims("Sumo", Boards::scrimsRows);
    }

    // the house board is scrims' rows in the house frame
    static Scoreboard tennacle() {
        return Frames.tennacle("Sumo", Boards::scrimsRows);
    }

    // no names: a duel's board is the clock
    private static void scrimsRows(Scoreboard b) {
        b.line((g, me) -> Component.text("Time: ", NamedTextColor.WHITE)
                .append(Frames.timeLeft(g)));
    }
}
