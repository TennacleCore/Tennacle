package tennacle.games;

import io.github.term4.grotto.Game;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The body a viewer wears: adventure, invisible, hovering, landed inside the player they asked for. Grotto
 * keeps their hands off the map; this keeps what they walked in with and hands it back on the way out.
 */
public final class Spectators {

    private record Worn(GameMode gamemode, boolean allowFlying, boolean flying, boolean invisible) {}

    private static final Map<UUID, Worn> worn = new ConcurrentHashMap<>();

    private Spectators() {}

    public static void install() {
        MinecraftServer.getGlobalEventHandler().addListener(PlayerDisconnectEvent.class, e -> worn.remove(e.getPlayer().getUuid()));
    }

    /** Puts {@code viewer} on {@code game}'s main world at {@code target}'s position, wearing the viewer's body. */
    public static void enter(@NotNull Game game, @NotNull Player viewer, @NotNull Player target) {
        worn.putIfAbsent(viewer.getUuid(), new Worn(viewer.getGameMode(), viewer.isAllowFlying(), viewer.isFlying(), viewer.isInvisible()));
        viewer.setGameMode(GameMode.ADVENTURE);
        viewer.setInvisible(true);
        viewer.setAllowFlying(true);
        viewer.setFlying(true);
        game.spectate(viewer, target.getPosition());
    }

    /** Drops the view and gives the body back; {@code false} = was not spectating. */
    public static boolean exit(@NotNull Player viewer) {
        Game watched = Matches.grotto().spectating(viewer);
        Worn was = worn.remove(viewer.getUuid());
        if (watched == null && was == null) return false;
        if (watched != null) watched.unspectate(viewer);
        if (was != null) {
            viewer.setInvisible(was.invisible());
            viewer.setGameMode(was.gamemode());
            viewer.setAllowFlying(was.allowFlying());
            viewer.setFlying(was.flying() && was.allowFlying());
        }
        return true;
    }
}
