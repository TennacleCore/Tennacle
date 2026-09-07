package tennacle.games;

import tennacle.games.map.Maps;
import tennacle.looks.Look;
import io.github.term4.grotto.GameType;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.MechanicsProfile;
import net.kyori.adventure.text.Component;
import io.github.term4.archipelago.replay.Replay;
import io.github.term4.canvas.theme.Theme;
import net.minestom.server.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A game: its type, where it plays, and the gamerules it can run under. {@code base} is what the mode IS on every
 * platform; {@code variants} are the named rulesets a platform selects, each already folded over the base;
 * {@code profile} is the mechanics the fold starts from (a preset, or nothing over the server global). What a
 * match looks like is a {@link Look}, chosen separately.
 *
 * @param ghostLoser death animation + observer spectate for an eliminated player; off = everyone stays put
 * @param intro      the instruction lines hypixel's game-start block shows; empty skips the block
 * @param teamScore  the per-team score key of a SCORING mode (hypixel's "TEAM WINS!" verdict); null = a plain duel
 * @param tabStats   the seat counters the house tab menu lists, in order; empty = no line
 * @param marks      how a replay's bookmarks read on the bookmark screen; null = the name on paper
 * @param look       the look every match of this mode wears whatever preset it runs; null = the preset's own,
 *                   which is what a mode with captures behind its platforms wants
 */
public record Mode(@NotNull String name, @NotNull GameType type, @NotNull Maps arena,
                   @NotNull Supplier<MechanicsProfile.Builder> profile,
                   @NotNull Ruleset base, @NotNull Map<String, Ruleset> variants,
                   @NotNull List<Component> intro, boolean ghostLoser, @Nullable StateKey<Integer> teamScore,
                   @NotNull List<SeatStat> tabStats, @Nullable Marks marks, @Nullable String look) {

    /** A mode whose looks are its platforms' - every mode with captures behind them. */
    public Mode(@NotNull String name, @NotNull GameType type, @NotNull Maps arena,
                @NotNull Supplier<MechanicsProfile.Builder> profile,
                @NotNull Ruleset base, @NotNull Map<String, Ruleset> variants,
                @NotNull List<Component> intro, boolean ghostLoser, @Nullable StateKey<Integer> teamScore,
                @NotNull List<SeatStat> tabStats, @Nullable Marks marks) {
        this(name, type, arena, profile, base, variants, intro, ghostLoser, teamScore, tabStats, marks, null);
    }

    /** A seat's own counter under a label ({@code Kills: 3}) - the viewer's, never the team's. */
    public record SeatStat(@NotNull String label, @NotNull StateKey<Integer> key) {}

    /** A replay bookmark as an item: the mode wrote the bookmark's data, so it alone can read it back. */
    @FunctionalInterface
    public interface Marks {
        @NotNull ItemStack render(@NotNull Replay.Bookmark mark, @NotNull Theme theme);
    }

    public Mode {
        // the pool holds a half-built map out of rotation rather than letting a match die on it
        if (arena instanceof tennacle.games.map.MapPool pool) pool.serves(type);
        variants = Map.copyOf(variants);
        intro = List.copyOf(intro);
        tabStats = List.copyOf(tabStats);
    }

    /** {@code Bridge} for {@code bridge}: the name as copy reads it. */
    public @NotNull String title() {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** The ruleset a platform names, or the base when this mode has no variant of that name. */
    public @NotNull Ruleset ruleset(@NotNull String name) {
        return variants.getOrDefault(name, base);
    }
}
