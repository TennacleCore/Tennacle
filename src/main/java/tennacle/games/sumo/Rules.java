package tennacle.games.sumo;

import tennacle.games.Duels;

import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.Markers;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.api.event.damage.DamageEvent;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerHandAnimationEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import org.jetbrains.annotations.NotNull;

/** Zero damage, full knockback; out of the ring (or out of the game) = the other player wins. */
public final class Rules implements GameModule {

    private static final String TIME_UP_ALARM = "sumo:time-up";

    @Override
    public void install(@NotNull Game game) {
        RegionMarker ring = game.arena().markers(Markers.BOUNDS).getFirst();
        // shoves never hurt; non-melee types are off in the world profile
        game.on(DamageEvent.class, DamageEvent::target, e -> {
            e.amount(0);
            if (e.source() instanceof Player attacker) Duels.addSeat(game, attacker, Sumo.LANDED);
        });
        game.on(PlayerHandAnimationEvent.class, e -> {
            if ("fighting".equals(game.state().get(Scope.GAME, Duels.PHASE))) Duels.addSeat(game, e.getPlayer(), Sumo.SWINGS);
        });
        game.on(PlayerMoveEvent.class, e -> {
            if (!"fighting".equals(game.state().get(Scope.GAME, Duels.PHASE))) return;
            if (ring.contains(e.getNewPosition())) return;
            Duels.settle(game, e.getPlayer(), "was knocked out of the ring by");
        });
        game.state().alarms().register(TIME_UP_ALARM, (tick, payload) -> {
            if (!"fighting".equals(game.state().get(Scope.GAME, Duels.PHASE))) return;
            Duels.draw(game);
        });
        game.state().alarms().arm(TIME_UP_ALARM, game.state().tick()
                + tennacle.games.Duels.START_GRACE_TICKS
                + game.rules().resolve(Scope.GAME, Sumo.LIMIT)); // the clock starts with the first beat
    }


    @Override
    public void onLeave(@NotNull Game game, @NotNull Player quitter, @NotNull Game.Departure departure) {
        Duels.settleOnLeave(game, quitter, departure);
    }
}
