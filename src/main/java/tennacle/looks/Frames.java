package tennacle.looks;

import org.jetbrains.annotations.NotNull;
import tennacle.games.Mode;
import tennacle.Msg;
import tennacle.games.Match;

import io.github.term4.canvas.board.Board;
import io.github.term4.canvas.board.Shine;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.codec.Codec;
import java.util.List;
import java.util.Locale;
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

    /** A date as the look's frame writes it: hypixel's US shape, ISO elsewhere. */
    public static @NotNull String date(@NotNull String look, @NotNull LocalDate day) {
        return "hypixel".equals(look) ? day.format(US_DATE) : day.toString();
    }

    /** A clock's color stops: {@code "green", "10:00 yellow", "5:00 red"} - see {@link #timeColor(List, long)}. */
    public static final StateKey<List<String>> CLOCK = StateKey.of("clock.colors", Codec.STRING.list(),
            List.of("green", "10:00 yellow", "5:00 red"));

    private Frames() {}

    /** Every board shouts its game, hypixel's captured titles included - one house rule, not a per-mode string. */
    private static String shout(String title) {
        return title.toUpperCase(Locale.ROOT);
    }

    /** Minemen: purple bold title, gray 19-hyphen rules, your/their ping, purple footer. */
    public static Scoreboard minemen(String title, Consumer<Scoreboard> content) {
        Component rule = Msg.MINEMEN.rule(19, NamedTextColor.GRAY);
        Scoreboard board = Scoreboard.of(Component.text(shout(title), NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
                .line(rule)
                // the capture showed the code alone; a board nobody can date is no use in a screenshot
                .line((g, me) -> Msg.MINEMEN.prose(LocalDate.now().toString())
                        .append(Msg.MINEMEN.dim(" " + g.code())));
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
        Scoreboard board = Scoreboard.of((g, me) -> Shine.HYPIXEL.frame(shout(shineName), (int) (g.tick() / 2)))
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
        Scoreboard board = Scoreboard.of(me -> Msg.SCRIMS.boardTitle(me, shout(title)))
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

    /** Tennacle: the scrims frame on the house theme - the gradient title, the address as the footer. */
    public static Scoreboard tennacle(String title, Consumer<Scoreboard> content) {
        Scoreboard board = Scoreboard.of(me -> Msg.THEME.boardTitle(me, shout(title)))
                .width(100)
                .line(houseRule())
                .line((g, me) -> Msg.THEME.prose(LocalDate.now().toString()).append(Msg.THEME.dim(" " + g.code())))
                .blank();
        content.accept(board);
        return board
                .line(houseRule())
                .line(houseFooter());
    }

    /** The lobby sidebar in the same chrome, around the server's own rows. */
    public static Board tennacleLobby(Consumer<Board.Builder> content) {
        Board.Builder board = Board.builder()
                .title(me -> Msg.THEME.boardTitle(me, "TENNACLE"))
                .width(100)
                .line(houseRule());
        content.accept(board);
        return board.line(houseRule()).line(houseFooter()).build();
    }

    /** A scrims-shaped row on the house theme: white label, themed value. */
    public static Component row(String label, Object value) {
        return Component.text(label, NamedTextColor.WHITE).append(Msg.THEME.value(value));
    }

    private static Component houseRule() {
        return Msg.THEME.rule(16, NamedTextColor.DARK_AQUA);
    }

    private static Component houseFooter() {
        return Msg.THEME.accent(Look.ADDRESS);
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

    /** What a running clock is shaded, as the mode's {@link #CLOCK} stops say. */
    public static NamedTextColor timeColor(Game game, long ticksLeft) {
        return timeColor(game.rules().resolve(Scope.GAME, CLOCK), ticksLeft);
    }

    /**
     * The first stop with no mark is the color above every other; each {@code "<mark> <color>"} takes over as the
     * time remaining falls past it. A mark is {@code M:SS} or plain seconds. One color alone never changes.
     */
    public static NamedTextColor timeColor(List<String> stops, long ticksLeft) {
        long left = Math.max(0, ticksLeft);
        NamedTextColor picked = NamedTextColor.GREEN;
        for (String stop : stops) {
            int mark = stop.indexOf(' ');
            if (mark < 0) picked = named(stop, picked);
            else if (left <= ticks(stop.substring(0, mark))) picked = named(stop.substring(mark + 1), picked);
        }
        return picked;
    }

    // a stop nobody can read leaves the color it found: a board is no place to throw
    private static NamedTextColor named(String name, NamedTextColor fallback) {
        NamedTextColor color = NamedTextColor.NAMES.value(name.trim().toLowerCase(java.util.Locale.ROOT));
        return color != null ? color : fallback;
    }

    private static long ticks(String mark) {
        try {
            int colon = mark.indexOf(':');
            return colon < 0 ? Long.parseLong(mark.trim()) * 20
                    : (Long.parseLong(mark.substring(0, colon).trim()) * 60
                            + Long.parseLong(mark.substring(colon + 1).trim())) * 20;
        } catch (NumberFormatException unreadable) {
            return -1;
        }
    }

    /** Captured hypixel clock, both halves padded: {@code 01:59}. */
    public static String clock(long ticks) {
        long seconds = Math.max(0, ticks) / 20;
        return "%02d:%02d".formatted(seconds / 60, seconds % 60);
    }

    /** Captured scrims clock, minutes unpadded: {@code 1:59}. */
    public static String shortClock(long ticks) {
        long seconds = Math.max(0, ticks) / 20;
        return seconds / 60 + ":" + "%02d".formatted(seconds % 60);
    }
}
