package tennacle.games.items;

import tennacle.games.TeamBlocks;
import tennacle.games.map.Building;
import tennacle.games.map.Structures;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.api.event.projectile.ProjectileLaunchEvent;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.codec.Codec;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;
import net.minestom.server.sound.SoundEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Bridge Egg (captured 2026-09-25, 95 eggs): a vanilla egg that leaves the thrower's wool under where it was
 * five ticks before, an L of three per tick, for thirty ticks or until it hits something or leaves the build limits.
 * One throw a second; a refusal costs nothing. Any mode with building rules may list it.
 */
public final class BridgeEgg implements GameModule {

    public static final StateKey<Long> COOLDOWN = StateKey.of("bridge-egg.cooldown", Codec.LONG, 20L);
    public static final StateKey<Integer> LIFE = StateKey.of("bridge-egg.life", Codec.INT, 30);
    /** Ticks between where the egg was and the wool there. */
    public static final StateKey<Integer> DELAY = StateKey.of("bridge-egg.delay", Codec.INT, 5);

    static final Sound BLOCK = Sound.sound(SoundEvent.ENTITY_CHICKEN_EGG, Sound.Source.MASTER, 1f, 1f);

    private final Map<Player, Long> thrown = new ConcurrentHashMap<>();
    private Game game;
    private Structures structures;
    private Building building;

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(COOLDOWN, LIFE, DELAY);
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
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
            trail(thrower, e.projectile());
        });
    }

    @Override
    public void onEnd(@NotNull Game game, @NotNull Outcome outcome) {
        thrown.clear();
    }

    /** One throw a cooldown; a refused throw is told so and keeps its egg. */
    public boolean allow(Player thrower) {
        if (game.roster().slotOf(thrower) == null) return false;
        long cooldown = game.rules().resolve(thrower, COOLDOWN);
        Long last = thrown.get(thrower);
        if (last != null && game.tick() - last < cooldown) {
            thrower.sendMessage(ItemLines.of(game).wait(game, cooldown));
            return false;
        }
        thrown.put(thrower, game.tick());
        return true;
    }

    /** Follows {@code egg} from the next tick, laying {@code thrower}'s wool under its path. */
    public void trail(Player thrower, Entity egg) {
        Slot seat = game.roster().slotOf(thrower);
        if (seat == null) return;
        Block wool = TeamBlocks.wool(game, seat);
        int life = game.rules().resolve(thrower, LIFE);
        int delay = game.rules().resolve(thrower, DELAY);
        Deque<Pos> path = new ArrayDeque<>();
        Pos thrown = egg.getPosition();
        structures.add(() -> {
            if (!egg.isRemoved()) {
                Pos at = egg.getPosition();
                if (at.samePoint(thrown)) return true; // captured: the first wool is a tick into the flight, not the throw
                if (egg.getAliveTicks() > life || !building.space().buildable(thrower, at)) egg.remove();
                else path.add(at);
            }
            if (path.size() > delay || egg.isRemoved() && !path.isEmpty()) lay(thrower, path.poll(), wool);
            return !(egg.isRemoved() && path.isEmpty());
        });
    }

    private void lay(Player thrower, Pos at, Block wool) {
        for (BlockVec cell : cells(at)) {
            if (!building.place(thrower, cell, wool)) continue;
            game.forEachAudience(v -> v.playSound(BLOCK, cell.x(), cell.y(), cell.z()));
        }
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
