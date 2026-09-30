package tennacle.games.sumo;

import tennacle.games.Match;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.combat.KillCredit;
import io.github.term4.grotto.modules.outcome.LastStanding;
import io.github.term4.polyp.api.event.damage.DamageEvent;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Shoves never hurt; a fall past the map's void plane is the elimination, credited to the last shove. */
public final class Rules implements GameModule {

    private Game game;

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        game.on(DamageEvent.class, DamageEvent::target, e -> e.amount(0)); // non-melee types are off in the world profile
    }

    /** A member dropped past the void plane ({@link io.github.term4.grotto.modules.space.Limits}). */
    void fell(@NotNull Player fallen) {
        if (!Match.live(game) || game.roster().slotOf(fallen) == null) return;
        KillCredit credit = game.module(KillCredit.class);
        Player by = credit != null ? credit.creditFor(fallen) : null;
        if (by == null) by = Match.opponent(game, fallen); // captured: the line always names the other side
        game.module(LastStanding.class).out(fallen, LastStanding.Cause.eliminated(by, "was knocked out of the ring by"));
    }
}
