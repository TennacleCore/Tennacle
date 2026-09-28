package tennacle.games;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.polyp.entity.DroppedItemEntity;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.entity.EntityItemMergeEvent;
import net.minestom.server.event.item.PickupItemEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Timed drops at points. Each generator drops its stack every period while its pool has fewer than its cap of
 * their items lying near them, and restarts on demand - a fresh drop now, the clock from there. What drops,
 * how often and how many is the mode's, changed live. A pickup of one of these copies to teammates near the
 * item when {@link #share} says so.
 */
public final class Generators implements GameModule {

    /** How near a generator its items count against the cap, and a teammate a pickup, in blocks. */
    private static final double NEAR = 3.0;
    private static final String ALARM = "generators:";
    /** On an item a generator dropped: which. Anything else lying there (a spilled chest's iron) is nobody's. */
    private static final Tag<Generator> MADE_BY = Tag.Transient("tennacle:generated");

    /** Generators sharing one cap: their items lying near them count together. */
    public final class Pool {
        private int cap;
        private final List<Generator> members = new ArrayList<>();

        private Pool(int cap) {
            this.cap = cap;
        }

        public void cap(int cap) {
            this.cap = cap;
        }

        /** The pool's items still lying near their generators. */
        public int lying() {
            int n = 0;
            for (Generator member : members) n += member.lying();
            return n;
        }
    }

    /** One drop point. */
    public final class Generator {
        private final int index;
        private final Point at;
        private final Pool pool;
        private ItemStack stack;
        private long period;
        private long due = -1;
        private boolean dropped;

        private Generator(int index, Point at, ItemStack stack, long period, Pool pool) {
            this.index = index;
            this.at = at;
            this.stack = stack;
            this.period = period;
            this.pool = pool;
        }

        /** Ticks between drops from the next one; {@link #restart} applies it now. */
        public @NotNull Generator period(long ticks) {
            this.period = ticks;
            return this;
        }

        public @NotNull Generator stack(@NotNull ItemStack stack) {
            this.stack = stack;
            return this;
        }

        /** The first drop {@code firstIn} ticks from now, then every period. */
        public void start(long firstIn) {
            dropped = false;
            schedule(game.tick() + Math.max(1, firstIn));
        }

        /** A drop now, the clock from here. */
        public void restart() {
            game.state().alarms().cancel(ALARM + index);
            due = -1;
            dropped = true;
            drop();
            schedule(game.tick() + period);
        }

        /** Whether a drop's time has come since the start; the first, captured, is off the countdown's clock. */
        public boolean dropped() {
            return dropped;
        }

        public long period() {
            return period;
        }

        public void stop() {
            game.state().alarms().cancel(ALARM + index);
            due = -1;
        }

        /** Ticks until the next drop; {@code -1} while stopped. */
        public long due() {
            return due < 0 ? -1 : Math.max(0, due - game.tick());
        }

        /** This generator's items still lying near it, stacks counted whole. */
        public int lying() {
            int n = 0;
            for (Entity entity : game.instance().getNearbyEntities(at, NEAR)) {
                if (entity instanceof DroppedItemEntity item && item.getTag(MADE_BY) == this) n += item.getItemStack().amount();
            }
            return n;
        }

        private void schedule(long at) {
            game.state().alarms().cancel(ALARM + index);
            due = at;
            game.state().alarms().arm(ALARM + index, at, CompoundBinaryTag.builder().putLong("due", at).build());
        }

        // a capped generator keeps its clock: the countdown restarts with no drop, as captured
        private void fire(long tick, CompoundBinaryTag payload) {
            if (payload.getLong("due") != due || game.decided() != null) return;
            dropped = true;
            drop();
            schedule(tick + period);
        }

        private void drop() {
            if (pool.lying() >= pool.cap) return;
            DroppedItemEntity item = Generators.drop(game, at, stack);
            if (item != null) item.setTag(MADE_BY, this);
        }
    }

    private final List<Generator> generators = new ArrayList<>();
    private double shareRadius = -1;
    private Predicate<ItemStack> shared = stack -> false;
    private Game game;

    public static @NotNull Generators of() {
        return new Generators();
    }

    /** {@code stack} dropped at {@code at} as a generator drops it, counted by none. */
    public static @Nullable DroppedItemEntity drop(@NotNull Game game, @NotNull Point at, @NotNull ItemStack stack) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        // captured: the spawn carries vanilla's hop (0.2 up, a little sideways) and the next tick zeroes it, so the
        // item drops straight onto the point; each lies as its own entity, never folded into a stack
        Vec velocity = new Vec((rnd.nextDouble() - rnd.nextDouble()) * 0.1, 0.2, (rnd.nextDouble() - rnd.nextDouble()) * 0.1);
        DroppedItemEntity item = DroppedItemEntity.spawn(game.shard(), new Pos(at.x(), at.y(), at.z()), velocity, stack, null, 0);
        if (item != null) {
            item.setMergeable(false);
            item.setVelocity(Vec.ZERO);
        }
        return item;
    }

    /** A pickup of a {@code shared} stack made here copies it to every teammate within {@code radius} of the item. */
    public @NotNull Generators share(double radius, @NotNull Predicate<ItemStack> shared) {
        this.shareRadius = radius;
        this.shared = shared;
        return this;
    }

    public @NotNull Pool pool(int cap) {
        return new Pool(cap);
    }

    /** A generator at {@code at}, stopped until {@link Generator#start} or {@link Generator#restart}. */
    public @NotNull Generator add(@NotNull Point at, @NotNull ItemStack stack, long period, @NotNull Pool pool) {
        Generator generator = new Generator(generators.size(), at, stack, period, pool);
        generators.add(generator);
        pool.members.add(generator);
        game.state().alarms().register(ALARM + generator.index, generator::fire);
        return generator;
    }

    /** The generator {@code item} came out of, or null. */
    public @Nullable Generator made(@NotNull ItemEntity item) {
        return item.getTag(MADE_BY);
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        // a stack of another provenance never folds in: the count and the split stay the generator's own
        game.on(EntityItemMergeEvent.class, e -> {
            if (e.getEntity().getTag(MADE_BY) != e.getMerged().getTag(MADE_BY)) e.setCancelled(true);
        });
        // Polyp's pickup runs first and cancels a full inventory, so this is a pickup that landed
        game.on(PickupItemEvent.class, PickupItemEvent::getLivingEntity, e -> {
            if (shareRadius < 0 || !(e.getLivingEntity() instanceof Player picker)) return;
            ItemEntity item = e.getItemEntity();
            if (item.getTag(MADE_BY) == null || !shared.test(e.getItemStack())) return;
            String team = Match.teamOf(game, picker);
            if (team.isEmpty()) return;
            for (Player mate : game.members()) {
                if (mate == picker || !team.equals(Match.teamOf(game, mate))) continue;
                if (mate.getPosition().distance(item.getPosition()) > shareRadius) continue;
                mate.getInventory().addItemStack(e.getItemStack());
            }
        });
    }
}
