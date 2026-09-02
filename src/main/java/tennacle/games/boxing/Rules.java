package tennacle.games.boxing;

import tennacle.games.Match;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.GameState;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.api.event.damage.DamageAppliedEvent;
import io.github.term4.polyp.api.event.damage.DamageEvent;
import io.github.term4.polyp.mechanics.damage.DamageSystem;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;

/** The hit engine: no damage ever, every landed fresh hit counts, combos flip on the receiver. {@code Score} decides. */
public final class Rules implements GameModule {

    @Override
    public void install(@NotNull Game game) {
        // boxing never hurts; non-melee types are off in the world profile
        game.on(DamageEvent.class, DamageEvent::target, e -> e.amount(0));
        // the pipeline's verdict, not the attack packets: FRESH_DAMAGE = landed outside the i-frame window
        game.on(DamageAppliedEvent.class, DamageAppliedEvent::target, e -> {
            if (e.outcome() != DamageSystem.DamageOutcome.FRESH_DAMAGE || !Match.live(game)) return;
            if (!(e.source() instanceof Player attacker) || !(e.target() instanceof Player victim)) return;
            Slot mine = game.roster().slotOf(attacker);
            Slot theirs = game.roster().slotOf(victim);
            if (mine == null || theirs == null) return;
            GameState st = game.state();
            Scope me = Scope.slot(mine.index());
            Scope them = Scope.slot(theirs.index());
            if (attacker.isSprinting()) st.update(me, Boxing.SPRINT_HITS, hits -> hits + 1);
            int combo = st.get(me, Boxing.COMBO) + 1;
            st.set(me, Boxing.COMBO, combo);
            if (combo > st.get(me, Boxing.BEST_COMBO)) st.set(me, Boxing.BEST_COMBO, combo);
            st.set(them, Boxing.COMBO, 0);
            st.update(me, Boxing.HITS, hits -> hits + 1); // last: the write that may decide the game
        });
    }
}
