package tennacle.games.items;

import tennacle.games.map.Building;
import tennacle.games.map.Structures;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.mechanics.fluids.Fluids;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.codec.Codec;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.particle.Particle;
import net.minestom.server.sound.SoundEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The sponge (captured 2026-09-25): it lands like any block, then from {@code sponge.delay} ticks on, every
 * {@code sponge.step}, it drinks the next shell of the cube {@code sponge.radius} blocks out, with a click and a
 * cloud at every open cell of the shell; a step after the last it is gone with a splash. While it stands, no
 * water runs into its cube. Any mode with building rules may list it.
 */
public final class Sponge implements GameModule {

    public static final StateKey<Integer> DELAY = StateKey.of("sponge.delay", Codec.INT, 5);
    public static final StateKey<Integer> STEP = StateKey.of("sponge.step", Codec.INT, 8);
    public static final StateKey<Integer> RADIUS = StateKey.of("sponge.radius", Codec.INT, 4);

    static final Sound GONE = Sound.sound(SoundEvent.ENTITY_PLAYER_SPLASH, Sound.Source.MASTER, 0.6f, 2f);
    // captured pitch bytes, one a ring; a ring past the fourth keeps the last
    private static final int[] RING_PITCH = {110, 116, 123, 129};

    private Game game;
    private Structures structures;
    private Building building;

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(DELAY, STEP, RADIUS);
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        structures = game.module(Structures.class);
        building = Building.of(game);
        if (structures == null || building == null) throw new IllegalStateException("Sponge needs the mode's building rules and Structures installed before it");
        // after Building's own handler: a placement it refused never gets here
        game.on(PlayerBlockPlaceEvent.class, e -> {
            Player player = e.getPlayer();
            if (!e.getBlock().compare(Block.SPONGE) || game.roster().slotOf(player) == null || game.isGhost(player)) return;
            start(player, e.getBlockPosition());
        });
    }

    static Sound ring(int ring) {
        int pitch = RING_PITCH[Math.min(Math.max(ring, 1), RING_PITCH.length) - 1];
        return Sound.sound(SoundEvent.BLOCK_WOODEN_BUTTON_CLICK_ON, Sound.Source.MASTER, 0.5f, pitch / 63f);
    }

    /** A sponge {@code placer} just put at {@code at}; the block lands after the event, the first step reads it there. */
    public void start(Player placer, Point at) {
        int delay = game.rules().resolve(placer, DELAY);
        int step = game.rules().resolve(placer, STEP);
        int radius = game.rules().resolve(placer, RADIUS);
        BlockVec center = at.asBlockVec();
        Runnable release = building.still(p -> distance(center, p) <= radius);
        structures.add(new Structures.Job() {
            private int age;
            private int ring = 1;

            @Override
            public boolean tick() {
                ++age;
                if (!game.shard().getBlock(center).compare(Block.SPONGE)) { // taken away meanwhile
                    release.run();
                    return false;
                }
                if (age < delay + step * (ring - 1)) return true;
                if (ring <= radius) {
                    drink(center, ring++);
                    return true;
                }
                building.vanish(center);
                Point c = center.add(0.5, 0.5, 0.5);
                game.forEachAudience(v -> v.playSound(GONE, c.x(), c.y(), c.z()));
                release.run();
                return false;
            }
        });
    }

    private static int distance(Point a, Point b) {
        return Math.max(Math.abs(a.blockX() - b.blockX()), Math.max(Math.abs(a.blockY() - b.blockY()), Math.abs(a.blockZ() - b.blockZ())));
    }

    // the shell at `ring`: its water goes, its open cells cloud over
    private void drink(BlockVec center, int ring) {
        List<ParticlePacket> clouds = new ArrayList<>();
        for (int dx = -ring; dx <= ring; dx++) {
            for (int dy = -ring; dy <= ring; dy++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != ring) continue;
                    BlockVec cell = center.add(dx, dy, dz);
                    Block block = game.shard().getBlock(cell);
                    if (block.compare(Block.WATER)) {
                        building.vanish(cell);
                    } else if (Fluids.waterlogged(block)) {
                        game.shard().setBlock(cell, block.withProperty("waterlogged", "false"));
                    } else if (block.air()) {
                        clouds.add(new ParticlePacket(Particle.CLOUD, false, true, cell.add(0.5, 0.5, 0.5),
                                new Vec(0.08, 0.08, 0.08), 0.04f, 4));
                    }
                }
            }
        }
        Point c = center.add(0.5, 0.5, 0.5);
        Sound click = ring(ring);
        game.forEachAudience(v -> {
            v.playSound(click, c.x(), c.y(), c.z());
            for (ParticlePacket cloud : clouds) v.sendPacket(cloud);
        });
    }
}
