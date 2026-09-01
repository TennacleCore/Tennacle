package tennacle.games;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.GameState;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.api.event.knockback.KnockbackEvent;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.RelativeFlags;
import net.minestom.server.event.player.PlayerMoveEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** The countdown freeze: moves pinned back to the seat, knockback swallowed until the fight (and after it settles). */
public final class Freeze {

    private Freeze() {}

    public static void pin(Game game) {
        GameState st = game.state();
        // cancelling would resync with ABSOLUTE view (the client's look rewinds mid-flick); instead let
        // the packet land, then pin back to the seat with yaw/pitch as zero RELATIVE deltas
        Set<UUID> pinning = new HashSet<>();
        game.on(PlayerMoveEvent.class, e -> {
            if (!"countdown".equals(st.get(Scope.GAME, Duels.PHASE))) return;
            Player p = e.getPlayer();
            Pos from = p.getPosition();
            Pos to = e.getNewPosition();
            if (from.x() == to.x() && from.y() == to.y() && from.z() == to.z()) return;
            Slot seat = game.roster().slotOf(p);
            Pos anchor = seat != null && seat.spawn() != null ? seat.spawn() : from;
            if (!pinning.add(p.getUuid())) return;
            MinecraftServer.getSchedulerManager().scheduleEndOfTick(() -> {
                pinning.remove(p.getUuid());
                if (!"countdown".equals(st.get(Scope.GAME, Duels.PHASE))) return;
                p.teleport(anchor.withView(0, 0), null, RelativeFlags.VIEW);
            });
        });
        game.on(KnockbackEvent.class, KnockbackEvent::target, e -> {
            String phase = st.get(Scope.GAME, Duels.PHASE);
            if ("fighting".equals(phase)) return;
            // the settling tick's KB is the final hit - it lands; anything later in "done" is swallowed
            if ("done".equals(phase) && st.tick() == st.get(Scope.GAME, Duels.DONE_AT)) return;
            e.setCancelled(true);
        });
    }
}
