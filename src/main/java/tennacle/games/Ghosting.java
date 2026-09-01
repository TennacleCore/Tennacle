package tennacle.games;

import io.github.term4.grotto.Game;
import net.minestom.server.entity.Player;

import java.util.UUID;

/** The settle path's delayed ghosting, armed by {@link Countdown#GHOST_ALARM} with the loser's uuid. */
public final class Ghosting {

    private Ghosting() {}

    public static void install(Game game) {
        game.state().alarms().register(Countdown.GHOST_ALARM, (tick, payload) -> {
            UUID uuid = UUID.fromString(payload.getString("uuid"));
            Player p = game.firstMember(m -> m.getUuid().equals(uuid));
            if (p != null) game.ghost(p);
        });
    }
}
