package tennacle.games;

import io.github.term4.archipelago.shard.ShardTabList;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.Spectating;
import io.github.term4.polyp.platform.SharedTeam;
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
 * The body a viewer wears: adventure, hovering, landed inside the player they asked for, the health, hunger
 * and armor bars off a 1.8 screen ({@link SpectatorHud}). NOT invisible - the shard scoping already hides an
 * unbound viewer from the game's members, so the spectator still sees their own body, the scrims way. In the
 * game's tab they list grayed and italic, to the members and the other spectators; Grotto keeps their hands
 * off the map. This keeps what they walked in with and hands it back on the way out.
 */
public final class Spectators {

    private record Worn(GameMode gamemode, boolean allowFlying, boolean flying) {}

    private static final Map<UUID, Worn> worn = new ConcurrentHashMap<>();

    private Spectators() {}

    public static void install() {
        MinecraftServer.getGlobalEventHandler().addListener(PlayerDisconnectEvent.class, e -> worn.remove(e.getPlayer().getUuid()));
        // a spectator lists only in the game they watch (to its members and its other spectators), grayed
        ShardTabList.rule((viewer, subject) -> {
            Game watched = Matches.grotto().spectating(subject);
            if (watched == null) return null; // not a spectator: the viewer's scope decides
            if (Game.of(viewer) == watched) return Spectating.listed(watched); // spectate:listed - the members' view
            return Matches.grotto().spectating(viewer) == watched;
        });
    }

    /** Puts {@code viewer} on {@code game}'s main world wearing the viewer's body: at {@code target}'s position, or the map's landing. */
    public static void enter(@NotNull Game game, @NotNull Player viewer, @Nullable Player target) {
        worn.putIfAbsent(viewer.getUuid(), new Worn(viewer.getGameMode(), viewer.isAllowFlying(), viewer.isFlying()));
        viewer.setGameMode(GameMode.ADVENTURE);
        SpectatorHud.hide(viewer);
        SharedTeam.set(viewer, SharedTeam.Reason.SPECTATOR, true); // landed inside a player: no push, either way
        viewer.setAllowFlying(true);
        viewer.setFlying(true);
        game.spectate(viewer, target != null ? target.getPosition() : null); // Nametags greys the tab name
    }

    /** Drops the view and gives the body back; {@code false} = was not spectating. */
    public static boolean exit(@NotNull Player viewer) {
        Game watched = Matches.grotto().spectating(viewer);
        Worn was = worn.remove(viewer.getUuid());
        if (watched == null && was == null) return false;
        if (watched != null) watched.unspectate(viewer);
        SpectatorHud.show(viewer);
        SharedTeam.set(viewer, SharedTeam.Reason.SPECTATOR, false);
        if (was != null) {
            viewer.setGameMode(was.gamemode());
            viewer.setAllowFlying(was.allowFlying());
            viewer.setFlying(was.flying() && was.allowFlying());
        }
        return true;
    }
}
