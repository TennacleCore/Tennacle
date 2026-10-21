package tennacle.games.stats;

import io.github.term4.grotto.Game;
import io.github.term4.polyp.Polyp;
import io.github.term4.polyp.api.event.damage.DamageAppliedEvent;
import io.github.term4.polyp.api.event.knockback.KnockbackAppliedEvent;
import io.github.term4.polyp.item.Enchants;
import io.github.term4.polyp.mechanics.attribute.catalog.enchant.Knockback;
import io.github.term4.polyp.tracking.SprintTracker;
import net.minestom.server.ServerFlag;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.potion.PotionEffect;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * A melee knockback as the victim's client played it, read off their packets (MCP-919). Ticks count the victim's
 * moves from the one the knockback first shows in: tick 0 is the first it could be fought.
 *
 * <p>Jump reset: the victim jumps on the first tick the knockback reaches it, still on the ground
 * (EntityLivingBase.onLivingUpdate): its first rise is the jump's 0.42, not the knockback's, and the next move keeps
 * the jump's arc. A jump made before the knockback arrived rises 0.42 too, but the knockback takes its next move.
 *
 * <p>Reduce: a click on a player-shaped entity while the client sprints, or holds a Knockback item, cuts its own
 * motion to 0.6 (EntityPlayer.attackTargetEntityWithCurrentItem), landed or not; the client only does so for an
 * EntityOtherPlayerMP. The click ends the sprint until the tick's update, so one per tick without Knockback. Clicks
 * precede the tick's move packet.
 */
public final class KnockReader {

    public enum Jump { RESET, EARLY, MISSED, IN_AIR, UNKNOWN }

    /** {@code counterTick}: the first tick the victim landed a hit back on the source, -1 for none. */
    public record Knock(@NotNull Player victim, @Nullable Entity source, long sentAt, long arrivedAt, @NotNull Jump jump,
                        @NotNull List<Integer> reduceTicks, int clicks, int counterTick, double pushed) {
        public int firstReduce() {
            return reduceTicks.isEmpty() ? -1 : reduceTicks.getFirst();
        }
    }

    public interface Listener {
        default void arrived(@NotNull Player victim, @NotNull Jump jump) {}

        default void reduced(@NotNull Player victim, int tick, int clicks, boolean first) {}

        default void ended(@NotNull Knock knock) {}
    }

    private static final double JUMP_RISE = 0.42;
    private static final double CLOSE = 0.002;
    private static final long ARRIVAL_TICKS = 10;
    private static final long WATCH_TICKS = 40;

    private enum Phase { SENT, JUMPED, LIVE }

    private static final class Group {
        int clicks;
        boolean sprintSpent, countered;
    }

    private static final class Watch {
        final @Nullable Entity source;
        final double hitRise, jumpRise, dirX, dirZ;
        final long sentAt;
        Phase phase = Phase.SENT;
        @Nullable Group jumpGroup;
        Pos start = Pos.ZERO;
        long arrivedAt;
        int tick;
        Jump jump = Jump.UNKNOWN;
        final List<Integer> reduceTicks = new ArrayList<>();
        int clicks;
        int counterTick = -1;
        double pushed;

        Watch(@Nullable Entity source, double hitRise, double jumpRise, double dirX, double dirZ, long sentAt) {
            this.source = source;
            this.hitRise = hitRise;
            this.jumpRise = jumpRise;
            this.dirX = dirX;
            this.dirZ = dirZ;
            this.sentAt = sentAt;
        }
    }

    private final Game game;
    private final Predicate<Player> watched;
    private final Listener listener;
    private final Map<UUID, Watch> watches = new ConcurrentHashMap<>();
    private final Map<UUID, Group> groups = new ConcurrentHashMap<>();

    private KnockReader(Game game, Predicate<Player> watched, Listener listener) {
        this.game = game;
        this.watched = watched;
        this.listener = listener;
    }

    /** Reads every melee knockback on a player {@code watched} accepts, for the life of {@code game}. */
    public static void install(@NotNull Game game, @NotNull Predicate<Player> watched, @NotNull Listener listener) {
        KnockReader reader = new KnockReader(game, watched, listener);
        game.on(KnockbackAppliedEvent.class, KnockbackAppliedEvent::target, reader::sent);
        game.on(EntityAttackEvent.class, EntityAttackEvent::getEntity, reader::clicked);
        game.on(DamageAppliedEvent.class, DamageAppliedEvent::target, reader::hitBack);
        game.on(PlayerMoveEvent.class, PlayerMoveEvent::getPlayer, reader::moved);
    }

    private void sent(KnockbackAppliedEvent e) {
        if (!e.snapshot().melee() || !(e.target() instanceof Player victim) || !watched.test(victim)) return;
        Watch old = watches.remove(victim.getUuid());
        if (old != null) end(victim, old);
        int boost = victim.getEffectLevel(PotionEffect.JUMP_BOOST);
        double jump = JUMP_RISE + (boost < 0 ? 0 : (boost + 1) * 0.1);
        double h = Math.hypot(e.velocity().x(), e.velocity().z());
        watches.put(victim.getUuid(), new Watch(e.snapshot().source(), e.velocity().y() / ServerFlag.SERVER_TICKS_PER_SECOND, jump,
                h > 0 ? e.velocity().x() / h : 0, h > 0 ? e.velocity().z() / h : 0, game.tick()));
    }

    private void clicked(EntityAttackEvent e) {
        if (!(e.getEntity() instanceof Player clicker) || e.getTarget().getEntityType() != EntityType.PLAYER) return;
        if (!watches.containsKey(clicker.getUuid())) return;
        Group group = groups.computeIfAbsent(clicker.getUuid(), id -> new Group());
        if (Enchants.level(clicker.getItemInMainHand(), Knockback.KEY) > 0) {
            group.clicks++;
        } else if (!group.sprintSpent && SprintTracker.isClientSprinting(Polyp.getInstance().sprintTracker(), clicker)) {
            group.sprintSpent = true;
            group.clicks++;
        }
    }

    private void hitBack(DamageAppliedEvent e) {
        if (!e.outcome().landed() || !(e.source() instanceof Player by)) return;
        Watch watch = watches.get(by.getUuid());
        if (watch == null || watch.source != e.target()) return;
        groups.computeIfAbsent(by.getUuid(), id -> new Group()).countered = true;
    }

    private void moved(PlayerMoveEvent e) {
        Player player = e.getPlayer();
        Group group = groups.remove(player.getUuid());
        Watch w = watches.get(player.getUuid());
        if (w == null) return;
        if (group == null) group = new Group();
        double dy = e.getNewPosition().y() - player.getPosition().y();
        long now = game.tick();
        switch (w.phase) {
            case SENT -> {
                if (now - w.sentAt > ARRIVAL_TICKS) {
                    watches.remove(player.getUuid());
                } else if (player.isOnGround() && close(dy, w.jumpRise) && !close(w.hitRise, w.jumpRise)) {
                    w.phase = Phase.JUMPED;
                    w.jumpGroup = group;
                    w.start = player.getPosition();
                } else if (close(dy, w.hitRise)) {
                    Jump jump = !player.isOnGround() ? Jump.IN_AIR : close(w.hitRise, w.jumpRise) ? Jump.UNKNOWN : Jump.MISSED;
                    arrive(player, w, jump, player.getPosition(), now);
                    step(player, w, group, e);
                }
            }
            case JUMPED -> {
                if (close(dy, (w.jumpRise - 0.08) * 0.98)) {
                    arrive(player, w, Jump.RESET, w.start, now - 1);
                    step(player, w, w.jumpGroup, null);
                    w.tick++;
                    step(player, w, group, e);
                } else if (close(dy, w.hitRise)) {
                    arrive(player, w, Jump.EARLY, player.getPosition(), now);
                    step(player, w, group, e);
                } else {
                    watches.remove(player.getUuid());
                }
            }
            case LIVE -> {
                w.tick++;
                step(player, w, group, e);
            }
        }
        if (w.phase == Phase.LIVE && (e.isOnGround() || now - w.arrivedAt > WATCH_TICKS)) {
            watches.remove(player.getUuid());
            end(player, w);
        }
    }

    private void arrive(Player player, Watch w, Jump jump, Pos start, long at) {
        w.phase = Phase.LIVE;
        w.jump = jump;
        w.start = start;
        w.arrivedAt = at;
        listener.arrived(player, jump);
    }

    private void step(Player player, Watch w, @Nullable Group group, @Nullable PlayerMoveEvent move) {
        if (group != null && group.clicks > 0) {
            boolean first = w.reduceTicks.isEmpty();
            w.reduceTicks.add(w.tick);
            w.clicks += group.clicks;
            listener.reduced(player, w.tick, group.clicks, first);
        }
        if (group != null && group.countered && w.counterTick < 0) w.counterTick = w.tick;
        if (move != null) {
            Pos to = move.getNewPosition();
            w.pushed = Math.max(w.pushed, (to.x() - w.start.x()) * w.dirX + (to.z() - w.start.z()) * w.dirZ);
        }
    }

    private void end(Player player, Watch w) {
        if (w.phase != Phase.LIVE) return;
        listener.ended(new Knock(player, w.source, w.sentAt, w.arrivedAt, w.jump, List.copyOf(w.reduceTicks), w.clicks,
                w.counterTick, w.pushed));
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < CLOSE;
    }
}
