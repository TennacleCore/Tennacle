package tennacle.games.items;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.api.event.projectile.ProjectileLaunchEvent;
import io.github.term4.polyp.mechanics.projectile.types.Fireball;
import net.minestom.server.codec.Codec;
import net.minestom.server.entity.Player;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * A fireball's throw slows its thrower (captured 2026-09-25: Slowness II for 20 ticks, particles shown, the effect
 * packet sent just before the fireball spawns; no attribute packet). The throw itself is Polyp's.
 */
public final class Fireballs implements GameModule {

    public static final StateKey<Long> SLOWNESS_TICKS = StateKey.of("fireball.slowness.ticks", Codec.LONG, 20L);
    /** Slowness level from 1; {@code 0} = no slowness. */
    public static final StateKey<Integer> SLOWNESS_LEVEL = StateKey.of("fireball.slowness.level", Codec.INT, 2);

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(SLOWNESS_TICKS, SLOWNESS_LEVEL);
    }

    @Override
    public void install(@NotNull Game game) {
        game.on(ProjectileLaunchEvent.class, ProjectileLaunchEvent::shooter, e -> {
            if (!(e.shooter() instanceof Player thrower) || !(e.snapshot().type() instanceof Fireball) || game.isGhost(thrower)) return;
            long ticks = game.rules().resolve(thrower, SLOWNESS_TICKS);
            int level = game.rules().resolve(thrower, SLOWNESS_LEVEL);
            if (ticks <= 0 || level <= 0) return;
            thrower.addEffect(new Potion(PotionEffect.SLOWNESS, level - 1, (int) ticks, (byte) (Potion.PARTICLES_FLAG | Potion.ICON_FLAG)));
        });
    }
}
