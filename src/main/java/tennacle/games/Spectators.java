package tennacle.games;

import io.github.term4.grotto.Game;
import io.github.term4.polyp.platform.compatibility.SpectatorHud;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The body a viewer wears: adventure and invisible, hovering, landed inside the player they asked for, with
 * the health, hunger and armor bars off their screen the way scrims does it ({@link SpectatorHud}: the
 * client is told a game mode it has no case for, the inventory stays the ordinary one). Grotto keeps their
 * hands off the map; this keeps what they walked in with and hands it back on the way out.
 */
public final class Spectators {

    private record Worn(GameMode gamemode, boolean allowFlying, boolean flying, boolean invisible) {}

    private static final Map<UUID, Worn> worn = new ConcurrentHashMap<>();

    private Spectators() {}

    public static void install() {
        MinecraftServer.getGlobalEventHandler().addListener(PlayerDisconnectEvent.class, e -> worn.remove(e.getPlayer().getUuid()));
    }

    /** Puts {@code viewer} on {@code game}'s main world wearing the viewer's body: at {@code target}'s position, or the map's landing. */
    public static void enter(@NotNull Game game, @NotNull Player viewer, @Nullable Player target) {
        worn.putIfAbsent(viewer.getUuid(), new Worn(viewer.getGameMode(), viewer.isAllowFlying(), viewer.isFlying(), viewer.isInvisible()));
        viewer.setGameMode(GameMode.ADVENTURE);
        viewer.setInvisible(true);
        SpectatorHud.hide(viewer);
        viewer.setAllowFlying(true);
        viewer.setFlying(true);
        game.spectate(viewer, target != null ? target.getPosition() : null);
    }

    /** Drops the view and gives the body back; {@code false} = was not spectating. */
    public static boolean exit(@NotNull Player viewer) {
        Game watched = Matches.grotto().spectating(viewer);
        Worn was = worn.remove(viewer.getUuid());
        if (watched == null && was == null) return false;
        if (watched != null) watched.unspectate(viewer);
        SpectatorHud.show(viewer);
        if (was != null) {
            viewer.setInvisible(was.invisible());
            viewer.setGameMode(was.gamemode());
            viewer.setAllowFlying(was.allowFlying());
            viewer.setFlying(was.flying() && was.allowFlying());
        }
        return true;
    }
}
