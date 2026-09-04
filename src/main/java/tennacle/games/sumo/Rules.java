package tennacle.games.sumo;

import tennacle.games.Match;

import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.Markers;
import io.github.term4.grotto.modules.combat.KillCredit;
import io.github.term4.grotto.modules.outcome.LastStanding;
import io.github.term4.polyp.api.event.damage.DamageEvent;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerHandAnimationEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import org.jetbrains.annotations.NotNull;

/** Shoves never hurt; a step off the ring is the elimination, credited to the last shove. */
public final class Rules implements GameModule {

    @Override
    public void install(@NotNull Game game) {
        RegionMarker ring = game.arena().markers(Markers.BOUNDS).getFirst();
        game.on(DamageEvent.class, DamageEvent::target, e -> {
            e.amount(0); // non-melee types are off in the world profile
            if (e.source() instanceof Player attacker) Match.addSeat(game, attacker, Sumo.LANDED);
        });
        game.on(PlayerHandAnimationEvent.class, e -> {
            if (Match.live(game)) Match.addSeat(game, e.getPlayer(), Sumo.SWINGS);
        });
        game.on(PlayerMoveEvent.class, e -> {
            if (!Match.live(game) || ring.contains(e.getNewPosition())) return;
            Player fallen = e.getPlayer();
            if (game.roster().slotOf(fallen) == null) return;
            KillCredit credit = game.module(KillCredit.class);
            Player by = credit != null ? credit.creditFor(fallen) : null;
            if (by == null) by = Match.opponent(game, fallen); // captured: the line always names the other side
            game.module(LastStanding.class).out(fallen, LastStanding.Cause.eliminated(by, "was knocked out of the ring by"));
        });
    }
}
