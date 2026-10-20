package tennacle.games.map;

import tennacle.games.stats.Haul;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.entity.PolypEntity;
import net.minestom.server.codec.Codec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * Removes what a game threw or dropped once it is past the build limit by {@link #MARGIN}, or in a chunk the map
 * never loaded, where Minestom would hold it frozen at the loaded edge for good. Mobs and players stay.
 */
public final class OffMap implements GameModule {

    /** How far past the build limit a thrown or dropped entity lasts, in blocks; below 0 = it is kept. */
    public static final StateKey<Integer> MARGIN = StateKey.of("space.despawn", Codec.INT, 50);

    private static final String SWEEP = "tennacle:off-map";

    /** A map's build limit, answered by the module that knows its shape. */
    public interface Limit {
        boolean beyond(@NotNull Point pos, double margin);
    }

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(MARGIN);
    }

    @Override
    public void install(@NotNull Game game) {
        game.state().alarms().register(SWEEP, (tick, payload) -> {
            game.state().alarms().arm(SWEEP, tick + 1);
            sweep(game);
        });
        game.state().alarms().arm(SWEEP, game.tick() + 1);
    }

    private static void sweep(Game game) {
        int margin = game.rules().resolve(Scope.GAME, MARGIN);
        if (margin < 0) return;
        List<Limit> limits = game.modules(Limit.class);
        Shard shard = game.shard();
        for (Entity entity : shard.entities()) {
            if (!(entity instanceof PolypEntity) || entity instanceof LivingEntity || entity.isRemoved()) continue;
            Pos at = entity.getPosition();
            if (!shard.isChunkLoaded(at) || beyond(limits, at, margin)) {
                Haul.offMap(game, entity);
                entity.remove();
            }
        }
    }

    private static boolean beyond(List<Limit> limits, Point pos, int margin) {
        for (Limit limit : limits) {
            if (limit.beyond(pos, margin)) return true;
        }
        return false;
    }
}
