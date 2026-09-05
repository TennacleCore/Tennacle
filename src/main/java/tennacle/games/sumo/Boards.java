package tennacle.games.sumo;

import tennacle.Msg;
import tennacle.games.Match;
import tennacle.looks.Frames;

import io.github.term4.grotto.Game;
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

    // the house frame: the clock, then each fighter in their tint
    static Scoreboard tennacle() {
        return Frames.tennacle("Sumo", b -> b
                .line((g, me) -> Frames.tennacleRow("Time ", Frames.timeLeft(g)))
                .blank()
                .line((g, me) -> playerLine(g, 0))
                .line((g, me) -> playerLine(g, 1)));
    }

    // the scrims frame (no sumo capture exists - their board look, sumo content)
    static Scoreboard scrims() {
        return Frames.scrims("Sumo", b -> b
                .line((g, me) -> Component.text("Time: ", NamedTextColor.WHITE)
                        .append(Frames.timeLeft(g)))
                .blank()
                .line((g, me) -> playerLine(g, 0))
                .line((g, me) -> playerLine(g, 1)));
    }

    private static Component playerLine(Game game, int index) {
        int[] left = {index};
        Player member = game.firstMember(m -> left[0]-- == 0);
        if (member == null) return Component.empty();
        var seat = game.roster().slotOf(member);
        return Component.text(member.getUsername(),
                game.roster().team(seat != null ? seat.team() : null).color());
    }
}
