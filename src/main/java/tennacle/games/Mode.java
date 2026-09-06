package tennacle.games;

import io.github.term4.grotto.GameType;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.MechanicsProfile;
import net.kyori.adventure.text.Component;
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
 */
public record Mode(@NotNull String name, @NotNull GameType type, @NotNull Maps arena,
                   @NotNull Supplier<MechanicsProfile.Builder> profile,
                   @NotNull Ruleset base, @NotNull Map<String, Ruleset> variants,
                   @NotNull List<Component> intro, boolean ghostLoser, @Nullable StateKey<Integer> teamScore,
                   @NotNull List<SeatStat> tabStats) {

    /** A seat's own counter under a label ({@code Kills: 3}) - the viewer's, never the team's. */
    public record SeatStat(@NotNull String label, @NotNull StateKey<Integer> key) {}

    public Mode {
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
