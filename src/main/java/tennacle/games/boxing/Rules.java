package tennacle.games.boxing;

import tennacle.games.Duels;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.GameState;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.api.event.damage.DamageAppliedEvent;
import io.github.term4.polyp.api.event.damage.DamageEvent;
import io.github.term4.polyp.mechanics.damage.DamageSystem;
import net.minestom.server.entity.Player;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import org.jetbrains.annotations.NotNull;

/** The hit engine: no damage ever, every landed fresh hit counts, combos flip on the receiver. */
public final class Rules implements GameModule {

    @Override
    public void install(@NotNull Game game) {
        // boxing never hurts; non-melee types are off in the world profile
        game.on(DamageEvent.class, DamageEvent::target, e -> e.amount(0));
        game.state().alarms().register("boxing:time-up", (tick, payload) -> timeUp(game));
        game.state().alarms().arm("boxing:time-up", // the clock starts with the first beat, like every duel
                game.state().tick() + Duels.START_GRACE_TICKS + game.rules().resolve(Scope.GAME, Boxing.LIMIT));
        // the pipeline's verdict, not the attack packets: FRESH_DAMAGE = landed outside the i-frame window
        game.on(DamageAppliedEvent.class, DamageAppliedEvent::target, e -> {
            if (e.outcome() != DamageSystem.DamageOutcome.FRESH_DAMAGE) return;
            if (!"fighting".equals(game.state().get(Scope.GAME, Duels.PHASE))) return;
            if (!(e.source() instanceof Player attacker) || !(e.target() instanceof Player victim)) return;
            Slot mine = game.roster().slotOf(attacker);
            Slot theirs = game.roster().slotOf(victim);
            if (mine == null || theirs == null) return;
            GameState st = game.state();
            Scope me = Scope.slot(mine.index());
            Scope them = Scope.slot(theirs.index());
            st.update(me, Boxing.HITS, hits -> hits + 1);
            if (attacker.isSprinting()) st.update(me, Boxing.SPRINT_HITS, hits -> hits + 1);
            int combo = st.get(me, Boxing.COMBO) + 1;
            st.set(me, Boxing.COMBO, combo);
            if (combo > st.get(me, Boxing.BEST_COMBO)) st.set(me, Boxing.BEST_COMBO, combo);
            st.set(them, Boxing.COMBO, 0);
            if (st.get(me, Boxing.HITS) >= game.rules().resolve(mine, Boxing.TARGET)) {
                Duels.settle(game, victim);
            }
        });
    }

    @Override
    public void onJoin(@NotNull Game game, @NotNull Player player, @NotNull Slot slot, boolean reclaimed) {
        player.addEffect(new Potion(PotionEffect.SPEED, 1, Potion.INFINITE_DURATION)); // Speed II
    }

    @Override
    public void onLeave(@NotNull Game game, @NotNull Player quitter, @NotNull Game.Departure departure) {
        quitter.removeEffect(PotionEffect.SPEED);
        Duels.settleOnLeave(game, quitter, departure);
    }

    private static void timeUp(Game game) {
        if (!"fighting".equals(game.state().get(Scope.GAME, Duels.PHASE))) return;
        Player loser = null;
        int lowest = Integer.MAX_VALUE;
        boolean tie = false;
        for (Player member : game.members()) {
            if (game.roster().slotOf(member) == null) continue;
            int hits = Boxing.hits(game, member);
            if (hits < lowest) { lowest = hits; loser = member; tie = false; }
            else if (hits == lowest) tie = true;
        }
        if (loser != null && !tie) {
            Duels.settle(game, loser);
            return;
        }
        Duels.draw(game);
    }

    @Override
    public void onEnd(@NotNull Game game, @NotNull Outcome outcome) {
        Duels.each(game, p -> p.removeEffect(PotionEffect.SPEED));
    }
}
