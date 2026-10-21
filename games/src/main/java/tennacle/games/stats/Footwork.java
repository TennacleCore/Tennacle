package tennacle.games.stats;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.polyp.Polyp;
import io.github.term4.polyp.api.event.damage.DamageAppliedEvent;
import io.github.term4.polyp.tracking.SprintTracker;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Knockback technique as stats: jump resets and reduces off {@link KnockReader}, and w-taps. A landed sprint hit
 * takes the attacker's sprint; the next one only has it again when the client stopped and restarted sprinting in
 * between.
 */
public final class Footwork implements GameModule {

    private static final long FOLLOW_UP_TICKS = 40;

    private final Map<UUID, Long> sprintHits = new ConcurrentHashMap<>();
    private Game game;

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        KnockReader.install(game, this::seated, new KnockReader.Listener() {
            @Override
            public void arrived(@NotNull Player victim, @NotNull KnockReader.Jump jump) {
                UUID id = victim.getUuid();
                Tally.add(game, id, "reduce-chances", 1);
                if (jump == KnockReader.Jump.RESET) Tally.add(game, id, "jump-resets", 1);
                if (jump == KnockReader.Jump.RESET || jump == KnockReader.Jump.MISSED) Tally.add(game, id, "jump-reset-chances", 1);
            }

            @Override
            public void reduced(@NotNull Player victim, int tick, int clicks, boolean first) {
                Tally.add(game, victim.getUuid(), "reduce-clicks", clicks);
                if (first) Tally.add(game, victim.getUuid(), "reduces", 1);
            }
        });
        game.on(DamageAppliedEvent.class, DamageAppliedEvent::target, e -> {
            if (!e.outcome().landed() || !(e.target() instanceof Player victim) || !(e.source() instanceof Player by)) return;
            if (by == victim || !seated(by) || !seated(victim) || Cause.of(e.type(), by) != Cause.MELEE) return;
            // before the hit takes the sprint
            boolean sprint = SprintTracker.wasRecentlySprinting(Polyp.getInstance().sprintTracker(), by, 0);
            Long last = sprintHits.remove(by.getUuid());
            if (last != null && game.tick() - last <= FOLLOW_UP_TICKS) {
                Tally.add(game, by.getUuid(), "w-tap-chances", 1);
                if (sprint) Tally.add(game, by.getUuid(), "w-taps", 1);
            }
            if (sprint) sprintHits.put(by.getUuid(), game.tick());
        });
    }

    private boolean seated(Player player) {
        return game.roster().slotOf(player) != null;
    }
}
