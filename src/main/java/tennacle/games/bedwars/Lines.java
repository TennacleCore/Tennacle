package tennacle.games.bedwars;

import tennacle.games.Match;

import io.github.term4.grotto.Game;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;

/** Bed Wars' captured lines for the timeline's events. */
final class Lines {

    private static final String[] NUMERALS = {"I", "II", "III"};

    private Lines() {}

    /** Captured: aqua or dark green {@code <Kind> Generators }, yellow {@code have been upgraded to Tier }, a red numeral. */
    static void tierUp(Game game, Mid.Kind kind, int tier) {
        boolean diamond = kind == Mid.Kind.DIAMOND;
        Match.say(game, Component.text(diamond ? "Diamond Generators " : "Emerald Generators ",
                        diamond ? NamedTextColor.AQUA : NamedTextColor.DARK_GREEN)
                .append(Component.text("have been upgraded to Tier ", NamedTextColor.YELLOW))
                .append(Component.text(NUMERALS[Math.max(1, Math.min(3, tier)) - 1], NamedTextColor.RED)));
    }

    /** Captured: red, in chat alone, five minutes before every bed goes. */
    static void bedsWarning(Game game) {
        Match.say(game, Component.text("All beds will be destroyed in 5 minutes!", NamedTextColor.RED));
    }

    /** Captured: red {@code Sudden Death} as a title, 10/30/10, no subtitle. The dragons are still to come. */
    static void suddenDeath(Game game) {
        Match.title(game, Title.title(Component.text("Sudden Death", NamedTextColor.RED), Component.empty(),
                Match.times(game, 10, 30, 10)));
    }
}
