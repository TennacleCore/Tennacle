package tennacle.games.beds;

import tennacle.looks.Look;
import tennacle.games.Matches;
import tennacle.looks.Palette;

import io.github.term4.canvas.chat.ChatWidth;
import io.github.term4.canvas.theme.Theme;
import tennacle.Msg;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.roster.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A bed mode's lines per network: the bed that went, the side that went with it, and the void's wording.
 * Minemen and hypixel wear their captured headlines; every other look reads the way bridge's banners do -
 * the line alone, centered between the theme's rules.
 */
public final class Banners {

    private Banners() {}

    /** Captured hypixel; minemen carries its own everywhere. */
    public static @NotNull Component bedBroken(Game game, Team side, Player breaker) {
        Component who = Component.text(breaker.getUsername(), tennacle.games.Match.teamColor(game, breaker));
        if (ruled(game)) {
            return ruled(Component.empty()
                    .append(Component.text(Palette.titled(side) + " Bed ", side.color()))
                    .append(prose(game, "was destroyed by ")).append(who).append(prose(game, "!")));
        }
        // minemen's guillemet takes no trailing space, so its bed carries the leading one
        String bed = minemen(game) ? " " + Palette.titled(side) + " Bed" : Palette.titled(side) + " Bed ";
        return framed(Component.empty().append(headline(game))
                .append(Component.text(bed, side.color()))
                .append(prose(game, minemen(game) ? " was destroyed by " : "was destroyed by "))
                .append(who).append(prose(game, "!")));
    }

    /** Every bed at once, on the destruction clock: captured hypixel, the headline over the mode's own words elsewhere. */
    public static @NotNull Component bedsFell(Game game) {
        if (hypixel(game)) return Component.text("All beds have been destroyed!", NamedTextColor.RED, TextDecoration.BOLD);
        if (ruled(game)) return ruled(prose(game, "Every bed was destroyed!"));
        return framed(Component.empty().append(headline(game))
                .append(prose(game, minemen(game) ? " Every bed was destroyed!" : "Every bed was destroyed!")));
    }

    /** Captured hypixel; minemen names no elimination in bed fight, so it gets none. */
    public static @Nullable Component eliminated(Game game, Team side) {
        if (minemen(game)) return null;
        if (ruled(game)) {
            return ruled(Component.text(Palette.titled(side) + " Team ", side.color())
                    .append(prose(game, "has been eliminated!")));
        }
        return framed(Component.empty()
                .append(Component.text("TEAM ELIMINATED > ").decorate(TextDecoration.BOLD))
                .append(Component.text(Palette.titled(side) + " Team ", side.color()))
                .append(Component.text("has been eliminated!", NamedTextColor.RED)));
    }

    /** How this network says a void death someone is credited for. */
    public static @NotNull String voidVerb(Game game) {
        return minemen(game) ? "was hit into the void by" : "was knocked into the void by";
    }

    /** Captured: hypixel shouts it, minemen does not. */
    public static @NotNull String finalNote(Game game) {
        return minemen(game) ? "FINAL KILL" : "FINAL KILL!";
    }

    // the looks bridge writes its own banners for: no captured headline, so they read as bridge's do
    private static boolean ruled(Game game) {
        return !minemen(game) && !hypixel(game);
    }

    // bridge's scored block, one line shorter: the theme's rules, the line centered between them
    private static Component ruled(Component line) {
        return Component.empty()
                .append(Msg.SCRIMS.rule()).append(Component.newline())
                .append(ChatWidth.center(line))
                .append(Component.newline()).append(Msg.SCRIMS.rule());
    }

    // captured minemen: a banner arrives with a blank line over and under it, so it reads apart from the chat
    private static Component framed(Component line) {
        return Component.text("\n").append(line).append(Component.text("\n"));
    }

    // minemen: light purple bold over a gray guillemet; everywhere else the captured hypixel plain bold. Bold is
    // the headline's ALONE, so every banner hangs its parts off an empty root rather than off this one
    private static Component headline(Game game) {
        if (!minemen(game)) return Component.text("BED DESTRUCTION > ").decorate(TextDecoration.BOLD);
        return Component.empty()
                .append(Component.text("BED DESTRUCTION ", NamedTextColor.LIGHT_PURPLE).decorate(TextDecoration.BOLD))
                .append(Component.text("\u00bb", NamedTextColor.GRAY).decorate(TextDecoration.BOLD));
    }

    // captured: minemen speaks these lines in yellow and hypixel in gray, whatever their boards read
    private static Component prose(Game game, String text) {
        if (minemen(game)) return Component.text(text, NamedTextColor.YELLOW);
        if (hypixel(game)) return Component.text(text, NamedTextColor.GRAY);
        return theme(game).prose(text);
    }

    private static Theme theme(Game game) {
        return Matches.lookOf(game).theme();
    }

    private static boolean minemen(Game game) {
        return Matches.lookOf(game).cue(Look.NAMED_ACCENT);
    }

    static boolean hypixel(Game game) {
        return Matches.lookOf(game).cue(Look.COUNT_DOTS);
    }
}
