package tennacle.games;

import io.github.term4.canvas.presets.HypixelCard;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.flow.Bundle;
import io.github.term4.grotto.modules.outcome.Results;
import io.github.term4.grotto.modules.view.Summary;
import io.github.term4.grotto.state.StateKey;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.games.map.Building;

import java.util.Locale;
import java.util.function.BiFunction;

/** What every mode's end cards share: the record behind a name, the play-again line, and the rows off {@link Hits} and {@link Building}. */
public final class Cards {

    private Cards() {}

    public static final Summary.SideCell DAMAGE = (g, side, th, c) -> th.hearts(Hits.hearts(g, side));
    public static final Summary.SideCell MELEE_ACCURACY =
            (g, side, th, c) -> th.value(Hits.share(Hits.sum(g, side, Hits.LANDED), Hits.sum(g, side, Hits.SWINGS)));
    public static final Summary.SideCell BOW_ACCURACY =
            (g, side, th, c) -> th.value(Hits.share(Hits.sum(g, side, Hits.ARROWS_HIT), Hits.sum(g, side, Hits.ARROWS)));
    public static final Summary.SideCell BLOCKS_PLACED = (g, side, th, c) -> th.value(Hits.sum(g, side, Building.PLACED_COUNT));
    public static final Summary.SideCell BLOCKS_BROKEN = (g, side, th, c) -> th.value(Hits.sum(g, side, Building.BROKEN_COUNT));
    public static final Summary.SideCell PRESSURE = (g, side, th, c) -> th.value(Match.pressure(g, side.getFirst().team()));

    public static final Summary.SeatCell DAMAGE_LINE =
            (g, seat, th, side) -> th.prose("Damage: ").append(th.hearts(Hits.hearts(g, java.util.List.of(seat))));
    public static final Summary.SeatCell BLOCKS_PLACED_LINE =
            (g, seat, th, side) -> th.prose("Blocks Placed: ").append(th.value(Match.seatStat(g, seat, Building.PLACED_COUNT)));
    public static final Summary.SeatCell BLOCKS_BROKEN_LINE =
            (g, seat, th, side) -> th.prose("Blocks Broken: ").append(th.value(Match.seatStat(g, seat, Building.BROKEN_COUNT)));
    /** Landed | taken. */
    public static final Summary.SeatCell HITS_LINE = (g, seat, th, side) -> th.prose("Hits: ")
            .append(th.value(Match.seatStat(g, seat, Hits.LANDED))).append(th.dim(" | ")).append(th.value(Match.seatStat(g, seat, Hits.TAKEN)));
    /** The share of hits taken behind a raised sword. */
    public static final Summary.SeatCell HITS_BLOCKED_LINE = (g, seat, th, side) -> th.prose("Hits Blocked: ")
            .append(th.value(Hits.share(Match.seatStat(g, seat, Hits.BLOCKED), Match.seatStat(g, seat, Hits.TAKEN))));
    public static final Summary.SeatCell ARROWS_LINE = (g, seat, th, side) -> {
        int hit = Match.seatStat(g, seat, Hits.ARROWS_HIT), shot = Match.seatStat(g, seat, Hits.ARROWS);
        return th.prose("Arrows: ").append(th.value(hit + "/" + shot)).append(th.dim(" (" + Hits.share(hit, shot) + ")"));
    };

    /** {@code kills | deaths (ratio)}. */
    public static @NotNull Summary.SeatCell kdr(@NotNull StateKey<Integer> kills, @NotNull StateKey<Integer> deaths) {
        return (g, seat, th, side) -> {
            int k = Match.seatStat(g, seat, kills), d = Match.seatStat(g, seat, deaths);
            return th.prose("KDR: ").append(th.value(k)).append(th.dim(" | ")).append(th.value(d))
                    .append(th.dim(" (" + String.format(Locale.ROOT, "%.1f", k / (double) Math.max(1, d)) + ")"));
        };
    }

    /**
     * The scrims duel card: the mode's lead row, then damage, pressure and blocks; the mode's lead card line, then
     * damage and KDR. The mode appends its own sections.
     */
    public static @NotNull Summary scrims(@NotNull String lead, @Nullable String leadInfo, @NotNull Summary.SideCell leadRow,
                                          @NotNull Summary.SeatCell leadLine, @NotNull StateKey<Integer> kills, @NotNull StateKey<Integer> deaths) {
        Summary card = Matches.summary();
        card = leadInfo == null ? card.row(lead, leadRow) : card.row(lead, leadInfo, leadRow);
        return card.row("Damage", DAMAGE)
                .row("Pressure", "The share of the game this side spent away from its own base", PRESSURE)
                .row("Blocks Placed", BLOCKS_PLACED)
                .row("Blocks Broken", BLOCKS_BROKEN)
                .cardSection(leadLine, DAMAGE_LINE, kdr(kills, deaths));
    }

    /** {@code card} with the frozen record behind every name; the record is first, so its snapshot precedes the card and cleanup. */
    public static @NotNull GameModule ends(@NotNull Summary card) {
        return ends(card, null);
    }

    /** As {@link #ends(Summary)}, with a stat item in the record's info row. */
    public static @NotNull GameModule ends(@NotNull Summary card, @Nullable BiFunction<Game, Player, ItemStack> item) {
        Results records = item == null ? Results.of().quiet() : Results.of().quiet().item(item);
        return Bundle.of(records, card.click((g, name) -> records.viewCommand(name)));
    }

    /** Hypixel's play-again line under a duel card: {@code /duel <opponent> <words>}. */
    public static @NotNull BiFunction<Game, Player, @Nullable Component> playAgain(@NotNull String words) {
        return (g, me) -> {
            String opponent = Match.opponentName(g, me);
            return opponent.isEmpty() ? null : HypixelCard.playAgain(Summary.won(g, me), "/duel " + opponent + " " + words);
        };
    }
}
