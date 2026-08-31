package tennacle.games.bridge;

import tennacle.Msg;
import tennacle.games.Duels;

import io.github.term4.canvas.theme.ChatWidth;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.GameState;
import io.github.term4.grotto.state.Scope;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Bridge's chat banners, kill lines and title text - all captured, per network. */
final class Banners {

    /** The gold frame around hypixel's scored block (green frames their end block). */
    private static final Component GOLD_RULE =
            Component.text("▬".repeat(64), NamedTextColor.GOLD, TextDecoration.BOLD);

    private Banners() {}

    // captured: "Dogln (20❤) scored!" then "1 - 0" between green rules, both lines centered
    static Component scrims(Game game, Player scorer, TextColor color) {
        var theme = Msg.SCRIMS;
        return Component.empty()
                .append(theme.rule()).append(Component.newline())
                .append(ChatWidth.center(Component.empty()
                        .append(Component.text(scorer.getUsername() + " ", color))
                        .append(Component.text("(", NamedTextColor.GRAY))
                        .append(Component.text(hearts(scorer), NamedTextColor.GREEN))
                        .append(Component.text("❤", NamedTextColor.RED))
                        .append(Component.text(") ", NamedTextColor.GRAY))
                        .append(Component.text("scored!", NamedTextColor.YELLOW))))
                .append(Component.newline())
                .append(ChatWidth.center(scoreLine(game, false, Bridge.teamOf(game, scorer))))
                .append(Component.newline()).append(theme.rule());
    }

    // captured: gold rules and blank lines around a bold team name, "(4❤)", yellow scored!,
    // gold "(1st Goal)", and the bold team-colored score
    static Component hypixel(Game game, Player scorer, TextColor color, int goal) {
        return Component.empty()
                .append(GOLD_RULE).append(Component.newline())
                .append(Component.newline())
                .append(ChatWidth.center(Component.empty()
                        .append(Component.text(scorer.getUsername(), color, TextDecoration.BOLD))
                        .append(Component.text(" (", NamedTextColor.GRAY))
                        .append(Component.text(hearts(scorer), NamedTextColor.GREEN))
                        .append(Component.text("❤", NamedTextColor.RED))
                        .append(Component.text(") ", NamedTextColor.GRAY))
                        .append(Component.text("scored! ", NamedTextColor.YELLOW))
                        .append(Component.text("(", NamedTextColor.GRAY))
                        .append(Component.text(ordinal(goal) + " Goal", NamedTextColor.GOLD))
                        .append(Component.text(")", NamedTextColor.GRAY))))
                .append(Component.newline())
                .append(ChatWidth.center(scoreLine(game, true, Bridge.teamOf(game, scorer))))
                .append(Component.newline())
                .append(Component.newline())
                .append(GOLD_RULE);
    }

    // minemen footage: only the name rides the team color, "scored!" stays yellow;
    // hypixel captured: the whole "<name> scored!" in the team color (scrims follows it)
    static Component scoredTitle(Game game, Player scorer, TextColor color) {
        return "minemen".equals(Duels.lookNameOf(game))
                ? Component.text(scorer.getUsername(), color)
                        .append(Component.text(" scored!", NamedTextColor.YELLOW))
                : Component.text(scorer.getUsername() + " scored!", color);
    }

    // captured per network - minemen: team names, yellow prose, "!"; scrims and hypixel share
    // team names, gray prose, "."
    // ported termbridge: gray prose, the number green at 4+, yellow at 3-2, red at 1
    static Component cageCount(Game game, int n) {
        NamedTextColor tone = n == 1 ? NamedTextColor.RED : n <= 3 ? NamedTextColor.YELLOW : NamedTextColor.GREEN;
        return Component.text("Cages open in ", NamedTextColor.GRAY)
                .append(Component.text(n, tone))
                .append(Component.text("...", NamedTextColor.GRAY));
    }

    static Component fight(Game game) {
        return Component.text("Fight!", NamedTextColor.GREEN);
    }

    /** {@link #scoredTitle} by name - the scorer may be gone by the time the re-cage count renders it. */
    static Component scoredHeld(Game game, String scorer, TextColor color) {
        return "minemen".equals(Duels.lookNameOf(game))
                ? Component.text(scorer, color).append(Component.text(" scored!", NamedTextColor.YELLOW))
                : Component.text(scorer + " scored!", color);
    }

    static Component fallLine(Game game, Player fallen, @Nullable Player by) {
        var roster = game.roster();
        Component name = Component.text(fallen.getUsername() + " ", roster.team(Bridge.teamOf(game, fallen)).color());
        if ("minemen".equals(Duels.lookNameOf(game))) {
            if (by == null) return name.append(Component.text("fell into the void!", NamedTextColor.YELLOW));
            return name.append(Component.text("was hit into the void by ", NamedTextColor.YELLOW))
                    .append(Component.text(by.getUsername(), roster.team(Bridge.teamOf(game, by)).color()))
                    .append(Component.text("!", NamedTextColor.YELLOW));
        }
        if (by == null) return name.append(Component.text("fell into the void.", NamedTextColor.GRAY));
        return name.append(Component.text("was knocked into the void by ", NamedTextColor.GRAY))
                .append(Component.text(by.getUsername(), roster.team(Bridge.teamOf(game, by)).color()))
                .append(Component.text(".", NamedTextColor.GRAY));
    }

    // captured: personal red bold KILL! line
    static Component kill(Game game, Player fallen) {
        return Component.text("KILL! ", NamedTextColor.RED, TextDecoration.BOLD)
                .append(Component.text(fallen.getUsername(),
                        game.roster().team(Bridge.teamOf(game, fallen)).color())
                        .decoration(TextDecoration.BOLD, false));
    }

    // captured: "1 - 0", the SCORER's team first - team-colored scores; scrims plain with white
    // hyphens, hypixel bold with gray
    static Component scoreLine(Game game, boolean hypixel, String leadTeam) {
        GameState st = game.state();
        Component line = Component.empty();
        List<Team> teams = new ArrayList<>(game.roster().teams());
        teams.sort(Comparator.comparing(t -> t.id().equals(leadTeam) ? 0 : 1));
        for (int i = 0; i < teams.size(); i++) {
            Team team = teams.get(i);
            if (i > 0) line = line.append(Component.text("- ",
                    hypixel ? NamedTextColor.GRAY : NamedTextColor.WHITE));
            Component score = Component.text(st.get(Scope.team(team.id()), Bridge.GOALS)
                    + (i < teams.size() - 1 ? " " : ""), team.color());
            if (hypixel) score = score.decorate(TextDecoration.BOLD);
            line = line.append(score);
        }
        return line;
    }

    /** Health with one trimmed decimal - captured: "24", "14.7", "6.7". */
    static String hearts(Player player) {
        float health = player.getHealth();
        return health == Math.floor(health) ? String.valueOf((int) health) : "%.1f".formatted(health);
    }

    private static String ordinal(int n) {
        if (n % 100 >= 11 && n % 100 <= 13) return n + "th";
        return n + switch (n % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; };
    }
}
