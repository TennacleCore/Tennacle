package tennacle.games.boxing;


import tennacle.games.Match;
import tennacle.games.Matches;

import io.github.term4.canvas.presets.HypixelCard;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.outcome.Results;
import io.github.term4.grotto.modules.view.Summary;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.component.DataComponents;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.util.List;

/** Boxing's end screens, one per network. */
final class Ends {

    private Ends() {}

    static Summary hypixel() {
        return Matches.summary().hypixel("Boxing Duel")
                .row("Hits", (g, side, th, c) -> th.value(stat(g, side.getFirst(), Boxing.HITS)))
                .row("Best Combo", (g, side, th, c) -> th.value(stat(g, side.getFirst(), Boxing.BEST_COMBO)))
                .row("W-Taps", (g, side, th, c) ->
                        th.value(Boxing.wtap(stat(g, side.getFirst(), Boxing.HITS), stat(g, side.getFirst(), Boxing.SPRINT_HITS))))
                .after((g, me) -> HypixelCard.playAgain(
                        Summary.won(g, me), "/duel " + Match.opponentName(g, me) + " boxing -preset=hypixel"));
    }

    // the same three stats under the house theme
    static Summary tennacle() {
        return scrims();
    }

    static Summary scrims() {
        return Matches.summary()
                .row("Hits", (g, side, th, c) -> th.value(stat(g, side.getFirst(), Boxing.HITS)))
                .row("Best Combo", (g, side, th, c) -> th.value(stat(g, side.getFirst(), Boxing.BEST_COMBO)))
                .row("W-Taps", "Sprint hits over total hits", (g, side, th, c) ->
                        th.value(Boxing.wtap(stat(g, side.getFirst(), Boxing.HITS), stat(g, side.getFirst(), Boxing.SPRINT_HITS))));
    }

    static Results results() {
        return Matches.results().item((g, p) -> {
            int hits = Boxing.seatStat(g, p, Boxing.HITS);
            String wtap = Boxing.wtap(hits, Boxing.seatStat(g, p, Boxing.SPRINT_HITS));
            return ItemStack.of(Material.DIAMOND_SWORD)
                    .with(DataComponents.CUSTOM_NAME, Component.text("Hits: ", NamedTextColor.YELLOW)
                            .append(Component.text(hits, NamedTextColor.LIGHT_PURPLE))
                            .decoration(TextDecoration.ITALIC, false))
                    .with(DataComponents.LORE, List.of(
                            Component.text("Best Combo: " + Boxing.seatStat(g, p, Boxing.BEST_COMBO), NamedTextColor.LIGHT_PURPLE)
                                    .decoration(TextDecoration.ITALIC, false),
                            Component.text("W-Taps: " + wtap, NamedTextColor.LIGHT_PURPLE)
                                    .decoration(TextDecoration.ITALIC, false)));
        });
    }

    private static int stat(Game game, Slot seat, StateKey<Integer> key) {
        return game.state().get(Scope.slot(seat.index()), key);
    }

}
