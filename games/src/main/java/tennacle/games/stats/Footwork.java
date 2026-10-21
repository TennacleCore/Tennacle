package tennacle.games.stats;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.polyp.Polyp;
import io.github.term4.polyp.api.event.damage.DamageAppliedEvent;
import io.github.term4.polyp.api.event.knockback.KnockbackAppliedEvent;
import io.github.term4.polyp.item.Enchants;
import io.github.term4.polyp.mechanics.attribute.catalog.enchant.Knockback;
import io.github.term4.polyp.tracking.SprintTracker;
import net.minestom.server.ServerFlag;
import net.minestom.server.entity.Player;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.potion.PotionEffect;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Knockback technique, read off the fighters' packets (MCP-919).
 *
 * <p>Jump reset: the victim jumps on the first tick the knockback reaches it, still on the ground
 * (EntityLivingBase.onLivingUpdate): its first rise is the jump's 0.42, not the knockback's, and the next move keeps
 * the jump's arc. A jump made before the knockback arrived rises 0.42 too, but the knockback takes its next move.
 *
 * <p>Reduce: a click on a player while the client sprints, or holds a Knockback item, cuts its own motion to 0.6
 * (EntityPlayer.attackTargetEntityWithCurrentItem), landed or not. The click ends the sprint until the tick's update,
 * so one per tick without Knockback. Clicks precede the tick's move packet.
 *
 * <p>W-tap: a landed sprint hit takes the attacker's sprint; the next one only has it again when the client stopped
 * and restarted sprinting in between.
 */
public final class Footwork implements GameModule {

    private static final double JUMP_RISE = 0.42;
    private static final double CLOSE = 0.002;
    private static final long WATCH_TICKS = 40;
    private static final long ARRIVAL_TICKS = 10;
    private static final long FOLLOW_UP_TICKS = 40;

    private record Jump(double hitRise, double jumpRise, long since, boolean jumped) {}

    private static final class Fighter {
        double hitRise, jumpRise;
        long sentAt = -1; // a knockback not yet in their moves
        long since = -1; // the knockback in play, until they land
        boolean reduced;
        int clicks;
        boolean sprintSpent;
        long sprintHitAt = -1;
    }

    private final Map<UUID, Jump> jumps = new ConcurrentHashMap<>();
    private final Map<UUID, Fighter> fighters = new ConcurrentHashMap<>();
    private Game game;

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        game.on(KnockbackAppliedEvent.class, KnockbackAppliedEvent::target, e -> {
            if (!e.snapshot().melee() || !(e.target() instanceof Player victim) || !seated(victim)) return;
            int boost = victim.getEffectLevel(PotionEffect.JUMP_BOOST);
            double jump = JUMP_RISE + (boost < 0 ? 0 : (boost + 1) * 0.1);
            double rise = e.velocity().y() / ServerFlag.SERVER_TICKS_PER_SECOND;
            jumps.put(victim.getUuid(), new Jump(rise, jump, game.tick(), false));
            Fighter f = fighter(victim);
            f.hitRise = rise;
            f.jumpRise = jump;
            f.sentAt = game.tick();
        });
        game.on(EntityAttackEvent.class, EntityAttackEvent::getEntity, e -> {
            if (!(e.getEntity() instanceof Player clicker) || !(e.getTarget() instanceof Player) || !seated(clicker)) return;
            Fighter f = fighter(clicker);
            if (Enchants.level(clicker.getItemInMainHand(), Knockback.KEY) > 0) {
                f.clicks++;
            } else if (!f.sprintSpent && SprintTracker.isClientSprinting(Polyp.getInstance().sprintTracker(), clicker)) {
                f.sprintSpent = true;
                f.clicks++;
            }
        });
        game.on(DamageAppliedEvent.class, DamageAppliedEvent::target, e -> {
            if (!e.outcome().landed() || !(e.target() instanceof Player victim) || !(e.source() instanceof Player by)) return;
            if (by == victim || !seated(by) || !seated(victim) || Cause.of(e.type(), by) != Cause.MELEE) return;
            // before the hit takes the sprint
            boolean sprint = SprintTracker.wasRecentlySprinting(Polyp.getInstance().sprintTracker(), by, 0);
            Fighter f = fighter(by);
            if (f.sprintHitAt >= 0 && game.tick() - f.sprintHitAt <= FOLLOW_UP_TICKS) {
                Tally.add(game, by.getUuid(), "w-tap-chances", 1);
                if (sprint) Tally.add(game, by.getUuid(), "w-taps", 1);
            }
            f.sprintHitAt = sprint ? game.tick() : -1;
        });
        game.on(PlayerMoveEvent.class, PlayerMoveEvent::getPlayer, e -> {
            double dy = e.getNewPosition().y() - e.getPlayer().getPosition().y();
            jumped(e.getPlayer(), dy);
            reduced(e, dy);
        });
    }

    private void jumped(Player player, double dy) {
        Jump watch = jumps.get(player.getUuid());
        if (watch == null) return;
        if (game.tick() - watch.since() > WATCH_TICKS) {
            jumps.remove(player.getUuid());
            return;
        }
        if (!watch.jumped()) {
            if (dy <= CLOSE) return; // moves sent before the knockback reached them
            jumps.remove(player.getUuid());
            if (!player.isOnGround()) return; // it reached them in the air
            if (close(dy, watch.jumpRise()) && !close(watch.hitRise(), watch.jumpRise())) {
                jumps.put(player.getUuid(), new Jump(watch.hitRise(), watch.jumpRise(), watch.since(), true));
            } else if (close(dy, watch.hitRise())) {
                Tally.add(game, player.getUuid(), "jump-reset-chances", 1);
            }
            return;
        }
        jumps.remove(player.getUuid());
        if (close(dy, (watch.jumpRise() - 0.08) * 0.98)) {
            Tally.add(game, player.getUuid(), "jump-resets", 1);
            Tally.add(game, player.getUuid(), "jump-reset-chances", 1);
        }
    }

    private void reduced(PlayerMoveEvent e, double dy) {
        Player player = e.getPlayer();
        Fighter f = fighters.get(player.getUuid());
        if (f == null) return;
        long now = game.tick();
        if (f.sentAt >= 0) {
            if (close(dy, f.hitRise) || (player.isOnGround() && close(dy, f.jumpRise))) {
                f.sentAt = -1;
                f.since = now;
                f.reduced = false;
                Tally.add(game, player.getUuid(), "reduce-chances", 1);
            } else if (now - f.sentAt > ARRIVAL_TICKS) {
                f.sentAt = -1;
            }
        }
        if (f.since >= 0 && f.clicks > 0) {
            Tally.add(game, player.getUuid(), "reduce-clicks", f.clicks);
            if (!f.reduced) Tally.add(game, player.getUuid(), "reduces", 1);
            f.reduced = true;
        }
        if (f.since >= 0 && (e.isOnGround() || now - f.since > WATCH_TICKS)) f.since = -1;
        f.clicks = 0;
        f.sprintSpent = false;
    }

    private Fighter fighter(Player player) {
        return fighters.computeIfAbsent(player.getUuid(), id -> new Fighter());
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < CLOSE;
    }

    private boolean seated(Player player) {
        return game.roster().slotOf(player) != null;
    }
}
