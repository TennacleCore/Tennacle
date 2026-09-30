package tennacle.games;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.GameState;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.api.event.damage.DamageAppliedEvent;
import io.github.term4.polyp.api.event.projectile.ProjectileLaunchEvent;
import io.github.term4.polyp.mechanics.damage.DamageSystem;
import io.github.term4.polyp.mechanics.damage.types.melee.MeleeDamage;
import io.github.term4.polyp.mechanics.projectile.entities.arrow.ArrowEntity;
import io.github.term4.polyp.mechanics.projectile.types.Arrow;
import net.minestom.server.codec.Codec;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerHandAnimationEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * Melee and bow numbers at the seats, off the damage pipeline's verdict rather than the attack packets: a hit
 * is one that landed outside the i-frame window. Boxing's engine; every mode's cards read them.
 */
public final class Hits implements GameModule {

    public static final StateKey<Integer> SWINGS = StateKey.of("ledger.hits.swings", Codec.INT, 0, true);
    public static final StateKey<Integer> LANDED = StateKey.of("ledger.hits.landed", Codec.INT, 0, true);
    public static final StateKey<Integer> TAKEN = StateKey.of("ledger.hits.taken", Codec.INT, 0, true);
    /** Taken behind a raised sword. */
    public static final StateKey<Integer> BLOCKED = StateKey.of("ledger.hits.blocked", Codec.INT, 0, true);
    public static final StateKey<Integer> SPRINT = StateKey.of("ledger.hits.sprint", Codec.INT, 0, true);
    /** Landed in a row; the receiver's own resets on the hit. */
    public static final StateKey<Integer> COMBO = StateKey.of("ledger.hits.combo", Codec.INT, 0, true);
    public static final StateKey<Integer> BEST_COMBO = StateKey.of("ledger.hits.best-combo", Codec.INT, 0, true);
    /** Health points dealt, melee and arrows alike. */
    public static final StateKey<Float> DEALT = StateKey.of("ledger.hits.dealt", Codec.FLOAT, 0f, true);
    public static final StateKey<Integer> ARROWS = StateKey.of("ledger.hits.arrows", Codec.INT, 0, true);
    public static final StateKey<Integer> ARROWS_HIT = StateKey.of("ledger.hits.arrows-hit", Codec.INT, 0, true);

    @Override
    public void install(@NotNull Game game) {
        game.on(PlayerHandAnimationEvent.class, e -> {
            if (Match.live(game)) Match.addSeat(game, e.getPlayer(), SWINGS);
        });
        game.onWorld(ProjectileLaunchEvent.class, ProjectileLaunchEvent::world, e -> {
            if (Match.live(game) && e.shooter() instanceof Player shooter && e.snapshot().type() == Arrow.INSTANCE) {
                Match.addSeat(game, shooter, ARROWS);
            }
        });
        game.on(DamageAppliedEvent.class, DamageAppliedEvent::target, e -> {
            if (e.outcome() != DamageSystem.DamageOutcome.FRESH_DAMAGE || !Match.live(game)) return;
            if (!(e.source() instanceof Player attacker) || !(e.target() instanceof Player victim)) return;
            Slot mine = game.roster().slotOf(attacker);
            Slot theirs = game.roster().slotOf(victim);
            if (mine == null || theirs == null) return;
            GameState st = game.state();
            Scope me = Scope.slot(mine.index());
            Scope them = Scope.slot(theirs.index());
            st.update(me, DEALT, dealt -> dealt + e.dealt());
            if (!MeleeDamage.KEY.equals(e.type().key())) {
                if (e.snapshot().detail() instanceof ArrowEntity) st.update(me, ARROWS_HIT, n -> n + 1);
                return;
            }
            if (attacker.isSprinting()) st.update(me, SPRINT, n -> n + 1);
            int combo = st.get(me, COMBO) + 1;
            st.set(me, COMBO, combo);
            if (combo > st.get(me, BEST_COMBO)) st.set(me, BEST_COMBO, combo);
            st.set(them, COMBO, 0);
            st.update(them, TAKEN, n -> n + 1);
            if (e.blocked()) st.update(them, BLOCKED, n -> n + 1);
            st.update(me, LANDED, n -> n + 1); // last: the write that may decide the game
        });
    }

    /** Over a side: a 1v1 side is one seat, a team side sums its seats. */
    public static int sum(@NotNull Game game, @NotNull List<Slot> side, @NotNull StateKey<Integer> key) {
        int total = 0;
        for (Slot seat : side) total += Match.seatStat(game, seat, key);
        return total;
    }

    /** Hearts dealt by a side, to a half. */
    public static @NotNull String hearts(@NotNull Game game, @NotNull List<Slot> side) {
        float points = 0;
        for (Slot seat : side) points += game.state().get(Scope.slot(seat.index()), DEALT);
        return String.format(Locale.ROOT, "%.1f", points / 2);
    }

    /** {@code landed} of {@code tried} as a percentage, or {@code N/A} before the first try. */
    public static @NotNull String share(int landed, int tried) {
        return tried > 0 ? Math.round(landed * 100.0 / tried) + "%" : "N/A";
    }
}
