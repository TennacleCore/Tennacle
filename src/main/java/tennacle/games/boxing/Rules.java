package tennacle.games.boxing;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.polyp.api.event.damage.DamageEvent;
import org.jetbrains.annotations.NotNull;

/** No damage ever; the hits are {@code Hits}', and {@code Score} decides on them. */
public final class Rules implements GameModule {

    @Override
    public void install(@NotNull Game game) {
        game.on(DamageEvent.class, DamageEvent::target, e -> e.amount(0)); // non-melee types are off in the world profile
    }
}
