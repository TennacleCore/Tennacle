package tennacle.games;

import io.github.term4.canvas.theme.Titles;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.Phases;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/** The phase names every duel mode shares, and the seat helpers rules and boards keep reaching for. */
public final class Match {

    public static final String OPENING = "opening";
    public static final String LIVE = "live";
    public static final String OVER = "over";

    private Match() {}

    public static boolean live(@NotNull Game game) {
        return Phases.is(game, LIVE);
    }

    public static boolean opening(@NotNull Game game) {
        return Phases.is(game, OPENING);
    }

    // game.members(), not shard members: ghosts still get announcements and end-of-game cleanup; spectators watch along
    public static void each(@NotNull Game game, @NotNull Consumer<Player> action) {
        game.forEachAudience(action);
    }

    /** The one other side in a 1v1 (ghosts included, so boards hold through the linger), or {@code null}. */
    public static @Nullable Player opponent(@NotNull Game game, @NotNull Player me) {
        Slot mine = game.roster().slotOf(me);
        if (mine == null) return null;
        return game.firstMember(p -> {
            Slot theirs = game.roster().slotOf(p);
            return theirs != null && !theirs.team().equals(mine.team());
        });
    }

    public static @NotNull String opponentName(@NotNull Game game, @NotNull Player me) {
        Player them = opponent(game, me);
        return them != null ? them.getUsername() : "";
    }

    /** The player's team id, or {@code ""} when they hold no seat. */
    public static @NotNull String teamOf(@NotNull Game game, @NotNull Player player) {
        Slot seat = game.roster().slotOf(player);
        return seat != null ? seat.team() : "";
    }

    public static @NotNull TextColor teamColor(@NotNull Game game, @NotNull Player player) {
        return game.roster().team(teamOf(game, player)).color();
    }

    /** Bumps a per-seat counter for {@code player}; a seatless player (spectator) counts nothing. */
    public static void addSeat(@NotNull Game game, @NotNull Player player, @NotNull StateKey<Integer> key) {
        Slot seat = game.roster().slotOf(player);
        if (seat == null) return;
        Scope scope = Scope.slot(seat.index());
        game.state().set(scope, key, game.state().get(scope, key) + 1);
    }

    /** Title times in GAME ticks, stretched to wall clock so a rate-scaled game's titles track its beats. */
    public static Title.Times times(@NotNull Game game, int fadeIn, int stay, int fadeOut) {
        int rate = game.tickRate();
        if (rate == net.minestom.server.ServerFlag.SERVER_TICKS_PER_SECOND) return Titles.times(fadeIn, stay, fadeOut);
        double ms = 1000.0 / rate;
        return Title.Times.times(java.time.Duration.ofMillis(Math.round(fadeIn * ms)),
                java.time.Duration.ofMillis(Math.round(stay * ms)),
                java.time.Duration.ofMillis(Math.round(fadeOut * ms)));
    }
}
