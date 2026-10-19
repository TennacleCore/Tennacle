package tennacle.games.items;

import tennacle.games.Throttle;
import tennacle.games.TeamBlocks;
import tennacle.games.map.Building;
import tennacle.games.map.Structures;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.api.event.projectile.ProjectileLaunchEvent;
import io.github.term4.polyp.mechanics.projectile.ProjectileConfigResolver.ResolvedFlight;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.codec.Codec;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;
import net.minestom.server.sound.SoundEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * The Bridge Egg (captured 2026-09-25, 95 eggs; 2026-10-19, thrown down while falling): a vanilla egg flying straight
 * down the thrower's look that leaves their wool under where it was four ticks before, an L of three per tick, for
 * thirty ticks or until it hits something or leaves the build limits.
 * One throw a second; a refusal costs nothing. Any mode with building rules may list it.
 */
public final class BridgeEgg implements GameModule {

    public static final StateKey<Long> COOLDOWN = StateKey.of("bridge-egg.cooldown", Codec.LONG, 20L);
    public static final StateKey<Integer> LIFE = StateKey.of("bridge-egg.life", Codec.INT, 30);
    /** Ticks between where the egg was and the wool there. Hypixel's read five or six from its spawn packet, which
     *  already carries the throw tick's step; from that step, four (every other tick five: it lays two ticks at once). */
    public static final StateKey<Integer> DELAY = StateKey.of("bridge-egg.delay", Codec.INT, 4);
    /** The wool starts at the egg's position this many ticks past its spawn. */
    public static final StateKey<Integer> START = StateKey.of("bridge-egg.start", Codec.INT, 1);

    static final Sound BLOCK = Sound.sound(SoundEvent.ENTITY_CHICKEN_EGG, Sound.Source.MASTER, 1f, 1f);

    private Game game;
    private static final StateKey<Long> USED = Throttle.stamp("ledger.items.egg.used");

    private Throttle throttle;
    private Structures structures;
    private Building building;

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(COOLDOWN, LIFE, DELAY, START);
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        throttle = Throttle.of(game, USED, COOLDOWN);
        structures = game.module(Structures.class);
        building = Building.of(game);
        if (structures == null || building == null) throw new IllegalStateException("BridgeEgg needs the mode's building rules and Structures installed before it");
        game.on(ProjectileLaunchEvent.class, ProjectileLaunchEvent::shooter, e -> {
            if (!(e.shooter() instanceof Player thrower) || e.snapshot().item() == null
                    || e.snapshot().item().material() != Material.EGG || game.isGhost(thrower)) return;
            if (!allow(thrower)) {
                e.setCancelled(true);
                return;
            }
            // captured: no spread, and announced a tick into its flight (9 of 9 straight down: (0, -1.515, 0) from the feet)
            ResolvedFlight flight = e.resolvedFlight();
            Vec aim = thrower.getPosition().direction().mul(flight.speed());
            e.setSpawnPos(e.spawnPos().add(aim));
            e.setVelocity(new Vec(aim.x() * flight.horizontalDrag(), aim.y() * flight.verticalDrag() - flight.gravity(),
                    aim.z() * flight.horizontalDrag()));
            e.projectile().setLaunchTickStep(false);
            trail(thrower, e.projectile(), e.spawnPos());
        });
    }

    /** One throw a cooldown; a refused throw is told so and keeps its egg. */
    public boolean allow(Player thrower) {
        return throttle.pass(thrower, left -> thrower.sendMessage(ItemLines.of(game).wait(game, throttle.cooldown(thrower))));
    }

    /** Follows {@code egg} from the next tick, laying {@code thrower}'s wool under its path from a tick past {@code thrown}. */
    public void trail(Player thrower, Entity egg, Point thrown) {
        Slot seat = game.roster().slotOf(thrower);
        if (seat == null) return;
        Block wool = TeamBlocks.wool(game, seat);
        int life = game.rules().resolve(thrower, LIFE);
        int delay = game.rules().resolve(thrower, DELAY);
        int start = game.rules().resolve(thrower, START);
        Deque<Pos> path = new ArrayDeque<>();
        int[] flown = {0};
        structures.add(() -> {
            if (!egg.isRemoved()) {
                Pos at = egg.getPosition();
                // captured 2026-09-28: the first wool sits a step past the spawn, which is itself a step in; the spawn's
                // cell (a 1/32 off on the wire grid) stays bare
                if (at.distanceSquared(thrown) < 0.01 || ++flown[0] < start) return true;
                if (egg.getAliveTicks() > life || !building.space().buildable(thrower, at)) egg.remove();
                else path.add(at);
            }
            if (path.size() > delay || egg.isRemoved() && !path.isEmpty()) lay(thrower, path.poll(), wool);
            return !(egg.isRemoved() && path.isEmpty());
        });
    }

    private void lay(Player thrower, Pos at, Block wool) {
        for (BlockVec cell : cells(at)) {
            if (inPlayer(cell) || !building.place(thrower, cell, wool)) continue;
            game.forEachAudience(v -> v.playSound(BLOCK, cell.x(), cell.y(), cell.z()));
        }
    }

    // captured 2026-10-19: in a falling thrower's column a three-cell gap rides with them, never a cell their box overlaps
    private boolean inPlayer(BlockVec cell) {
        for (Player member : game.members()) {
            if (game.isGhost(member)) continue;
            Pos p = member.getPosition();
            double half = member.getBoundingBox().width() / 2;
            if (p.x() + half > cell.x() && p.x() - half < cell.x() + 1 && p.z() + half > cell.z() && p.z() - half < cell.z() + 1
                    && p.y() + member.getBoundingBox().height() > cell.y() && p.y() < cell.y() + 1) return true;
        }
        return false;
    }

    /** The block under {@code at} and its nearer x and z neighbors. */
    static List<BlockVec> cells(Point at) {
        int y = (int) Math.floor(at.y() - 1.5);
        int x = at.blockX(), z = at.blockZ();
        int nx = (int) Math.floor(at.x() + 0.5) == x ? (int) Math.floor(at.x() - 0.5) : (int) Math.floor(at.x() + 0.5);
        int nz = (int) Math.floor(at.z() + 0.5) == z ? (int) Math.floor(at.z() - 0.5) : (int) Math.floor(at.z() + 0.5);
        return List.of(new BlockVec(x, y, z), new BlockVec(nx, y, z), new BlockVec(x, y, nz));
    }
}
