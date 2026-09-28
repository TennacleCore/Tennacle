package tennacle.games.items;

import tennacle.games.Matches;
import tennacle.looks.Look;

import io.github.term4.grotto.Game;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/** What the shared items say, answered by the game's look through {@link #CUE}; the captured wording is the fallback. */
public interface ItemLines {

    /** A use refused for its cooldown, with the whole cooldown named. */
    @NotNull Component wait(@NotNull Game game, long cooldownTicks);

    /** A creature set down, with its lifetime. */
    @NotNull Component creature(@NotNull Game game, long lifeTicks);

    ItemLines CAPTURED = new ItemLines() {
        @Override
        public @NotNull Component wait(@NotNull Game game, long cooldownTicks) {
            return Matches.lookOf(game).theme().bad(String.format(Locale.ROOT, "Please wait %.1fs to use that again", cooldownTicks / 20.0));
        }

        @Override
        public @NotNull Component creature(@NotNull Game game, long lifeTicks) {
            return Matches.lookOf(game).theme().good("Your creature will fight for you for " + (lifeTicks / 20) + " seconds.");
        }
    };

    Look.Cue<ItemLines> CUE = new Look.Cue<>("item-lines", CAPTURED);

    static @NotNull ItemLines of(@NotNull Game game) {
        return Matches.lookOf(game).cue(CUE);
    }
}
