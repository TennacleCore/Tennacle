package tennacle.games;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.api.event.item.ItemPickupEvent;
import net.minestom.server.codec.Codec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerMoveEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** A member who has not moved for {@link #PICKUP} ticks picks nothing up until they do (the user, 2026-09-28: Hypixel's 30 s). */
public final class Idle implements GameModule {

    /** Ticks of standing still after which pickups stop; {@code 0} = never. */
    public static final StateKey<Long> PICKUP = StateKey.of("pickup.idle", Codec.LONG, 600L);

    private final Map<UUID, Long> moved = new ConcurrentHashMap<>();
    private Game game;

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(PICKUP);
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        game.on(PlayerMoveEvent.class, e -> {
            Pos from = e.getPlayer().getPosition(), to = e.getNewPosition();
            if (from.x() != to.x() || from.y() != to.y() || from.z() != to.z()) moved.put(e.getPlayer().getUuid(), game.tick());
        });
        game.on(ItemPickupEvent.class, e -> {
            if (idle(e.getPlayer())) e.setCancelled(true);
        });
    }

    @Override
    public void onJoin(@NotNull Game game, @NotNull Player player, @NotNull Slot slot, boolean reclaimed) {
        moved.put(player.getUuid(), game.tick());
    }

    @Override
    public void onLeave(@NotNull Game game, @NotNull Player player, @NotNull Game.Departure departure) {
        moved.remove(player.getUuid());
    }

    /** Whether {@code player} has stood still past the rule. */
    public boolean idle(@NotNull Player player) {
        if (game.roster().slotOf(player) == null) return false;
        long after = game.rules().resolve(player, PICKUP);
        Long last = moved.get(player.getUuid());
        return after > 0 && last != null && game.tick() - last >= after;
    }
}
