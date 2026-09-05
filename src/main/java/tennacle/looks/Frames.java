package tennacle.looks;

import tennacle.Msg;
import tennacle.games.Match;

import io.github.term4.canvas.board.Shine;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.flow.TimeLimit;
import io.github.term4.grotto.modules.view.Scoreboard;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

/**
 * The captured board chrome per network - title, rules, date, ping and winstreak filler, footer.
 * A game pours only its own rows into {@code content}.
 */
public final class Frames {
    private static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("MM/dd/yy");

    private Frames() {}

    /** Minemen: purple bold title, gray 19-hyphen rules, your/their ping, purple footer. */
    public static Scoreboard minemen(String title, Consumer<Scoreboard> content) {
        Component rule = Msg.MINEMEN.rule(19, NamedTextColor.GRAY);
        Scoreboard board = Scoreboard.of(Component.text(title, NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
                .line(rule)
                .gameId(g -> Msg.MINEMEN);
        content.accept(board);
        return board
                .blank()
                .line((g, me) -> minemenRow("Your Ping: ", me.getLatency() + " ms"))
                .line((g, me) -> {
                    Player them = Match.opponent(g, me);
                    return minemenRow("Their Ping: ", them != null ? them.getLatency() + " ms" : "?");
                })
                .blank()
                .line((g, me) -> Component.text("tennacle", NamedTextColor.LIGHT_PURPLE))
                .line(rule);
    }

    /** Hypixel duels: shined title, date, Time Left, Mode + winstreak filler (until the vault tracks them). */
    public static Scoreboard hypixel(String shineName, String mode,
                                     @Nullable ToLongFunction<Game> ticksLeft, Consumer<Scoreboard> content) {
        Scoreboard board = Scoreboard.of((g, me) -> Shine.HYPIXEL.frame(shineName, (int) (g.tick() / 2)))
                .titleEvery(2)
                // date + gray game code, the captured hypixel layout - a screenshot names the game
                .line((g, me) -> Msg.HYPIXEL.prose(LocalDate.now().format(US_DATE))
                        .append(Msg.HYPIXEL.dim("  " + g.code())))
                .blank();
        if (ticksLeft != null) {
            board.line((g, me) -> hypixelRow("Time Left: ", timeLeft(g, ticksLeft.applyAsLong(g))))
                    .blank();
        }
        content.accept(board);
        return board
                .blank()
                .line((g, me) -> hypixelRow("Mode: ", mode))
                .line((g, me) -> hypixelRow("Overall Winstreak: ", 0))
                .line((g, me) -> hypixelRow("Mode Winstreak: ", 0))
                .blank()
                .line((g, me) -> Msg.HYPIXEL.accent("tennacle"));
    }

    /** Scrims: dark-aqua 16-hyphen rules, date, the captured 100px width. */
    public static Scoreboard scrims(String title, Consumer<Scoreboard> content) {
        Component rule = Msg.SCRIMS.rule(16, NamedTextColor.DARK_AQUA);
        Scoreboard board = Scoreboard.of(me -> Msg.SCRIMS.boardTitle(me, title))
                .width(100) // captured scrims board: the widest row runs 99px, just past its 16-hyphen rule
                .line(rule)
                // date + gray game code, the captured scrims layout - a screenshot names the game
                .line((g, me) -> Msg.SCRIMS.prose(LocalDate.now().toString())
                        .append(Msg.SCRIMS.dim(" " + g.code())))
                .blank();
        content.accept(board);
        return board
                .line(rule)
                .line((g, me) -> Msg.SCRIMS.value("tennacle"));
    }

    public static Component minemenRow(String label, Object value) {
        return minemenRow(label, Component.text(String.valueOf(value), NamedTextColor.LIGHT_PURPLE));
    }

    public static Component minemenRow(String label, Component value) {
        return Component.text(label, NamedTextColor.WHITE).append(value);
    }

    public static Component hypixelRow(String label, Object value) {
        return hypixelRow(label, Msg.HYPIXEL.value(value));
    }

    public static Component hypixelRow(String label, Component value) {
        return Component.text(label, NamedTextColor.WHITE).append(value);
    }

    /** The running clock in {@link #clock} form, shaded by {@link #timeColor}. */
    public static Component timeLeft(Game game) {
        return timeLeft(game, TimeLimit.left(game));
    }

    public static Component timeLeft(Game game, long ticksLeft) {
        return Component.text(clock(Math.max(0, ticksLeft)), timeColor(game, ticksLeft));
    }

    /**
     * Green through the first third of the clock, yellow through the second, red through the last - a 15:00 match
     * turns at 10:00 and again at 5:00, as captured. An unlimited match stays green.
     */
    public static NamedTextColor timeColor(Game game, long ticksLeft) {
        long left = Math.max(0, ticksLeft);
        long limit = TimeLimit.limit(game);
        if (limit <= 0 || left * 3 > limit * 2) return NamedTextColor.GREEN;
        return left * 3 > limit ? NamedTextColor.YELLOW : NamedTextColor.RED;
    }

    /** Captured hypixel clock, both halves padded: {@code 01:59}. */
    public static String clock(long ticks) {
        long seconds = ticks / 20;
        return "%02d:%02d".formatted(seconds / 60, seconds % 60);
    }
}
