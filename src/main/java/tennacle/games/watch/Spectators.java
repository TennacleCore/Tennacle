package tennacle.games.watch;

import tennacle.games.Matches;
import tennacle.games.Mode;
import io.github.term4.archipelago.shard.ShardTabList;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.modules.view.Spectating;
import io.github.term4.polyp.platform.SharedTeam;
import io.github.term4.polyp.platform.compatibility.SpectatorMode;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.event.player.PlayerSpectateEntityEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.Msg;

/**
 * A viewer enters as a {@link SpectatorMode.Mode#GHOST}: adventure, hovering, landed inside the player they asked
 * for, the stat bars off a 1.8 screen. NOT invisible - the shard scoping already hides an unbound viewer from the
 * game's members, so the spectator still sees their own body, the scrims way. In the game's tab they list grayed,
 * to the members and the other spectators; Grotto keeps their hands off the map. {@link #mode} switches them into
 * the client's TRUE spectator mode and back; a true spectator rides the camera of a member they attack-click.
 */
public final class Spectators {

    private Spectators() {}

    public static void install() {
        // a spectator's click is the 26.x spectate-entity packet, never an attack: ViaBackwards rewrites a legacy
        // spectator's attack into it too (EntityPacketRewriter26_1, by the game mode it tracks)
        MinecraftServer.getGlobalEventHandler().addListener(PlayerSpectateEntityEvent.class, e -> ride(e.getPlayer(), e.getTarget()));
        MinecraftServer.getGlobalEventHandler().addListener(EntityAttackEvent.class, e -> {
            if (e.getEntity() instanceof Player viewer) ride(viewer, e.getTarget());
        });
        // a spectator lists only in the game they watch (to its members and its other spectators), grayed
        ShardTabList.rule((viewer, subject) -> {
            Game watched = Matches.grotto().spectating(subject);
            if (watched == null) return null; // not a spectator: the viewer's scope decides
            if (Game.of(viewer) == watched) return Spectating.listed(watched); // spectate:listed - the members' view
            return Matches.grotto().spectating(viewer) == watched;
        });
    }

    // a true spectator rides the camera of a player in the game they watch; anyone else is not a target
    private static void ride(Player viewer, net.minestom.server.entity.Entity clicked) {
        if (SpectatorMode.mode(viewer) != SpectatorMode.Mode.TRUE || !(clicked instanceof Player target)) return;
        Game watched = Matches.grotto().spectating(viewer);
        if (watched != null && (Game.of(target) == watched || watched.isGhost(target))) SpectatorMode.camera(viewer, target);
    }

    /** Puts {@code viewer} on {@code game}'s main world as a ghost: at {@code target}'s position, or the map's landing. */
    public static void enter(@NotNull Game game, @NotNull Player viewer, @Nullable Player target) {
        SpectatorMode.enter(viewer, SpectatorMode.Mode.GHOST);
        SharedTeam.set(viewer, SharedTeam.Reason.SPECTATOR, true); // landed inside a player: no push, either way
        game.spectate(viewer, target != null ? target.getPosition() : null); // Nametags greys the tab name
    }

    /** Drops the view and gives the body back; {@code false} = was not spectating. */
    public static boolean exit(@NotNull Player viewer) {
        Game watched = Matches.grotto().spectating(viewer);
        boolean wore = SpectatorMode.exit(viewer); // the camera home and the body back before the world changes
        if (watched == null && !wore) return false;
        if (watched != null) watched.unspectate(viewer);
        SharedTeam.set(viewer, SharedTeam.Reason.SPECTATOR, false);
        return true;
    }

    /** Switches a spectating {@code viewer}'s body, telling them; {@code false} = not spectating. */
    public static boolean mode(@NotNull Player viewer, @NotNull SpectatorMode.Mode mode) {
        if (Matches.grotto().spectating(viewer) == null) return false;
        if (!SpectatorMode.enter(viewer, mode)) {
            Msg.fail(viewer, "Your client has no spectator mode (1.7).");
        } else if (mode == SpectatorMode.Mode.TRUE) {
            Msg.say(viewer, "Spectator mode: click a player to ride their camera, sneak to leave it. ",
                    Msg.run("/spec mode ghost"), " for the ghost body.");
        } else {
            Msg.say(viewer, "Ghost body. ", Msg.run("/spec mode true"), " for spectator mode.");
        }
        return true;
    }

    /**
     * A game-mode command from a spectator: {@code spectator} and {@code adventure} are the two bodies, anything
     * else is refused - the ghost's spoof would swallow it silently. {@code false} = not spectating, set it as asked.
     */
    public static boolean gamemode(@NotNull Player viewer, @NotNull GameMode asked) {
        if (Matches.grotto().spectating(viewer) == null) return false;
        switch (asked) {
            case SPECTATOR -> mode(viewer, SpectatorMode.Mode.TRUE);
            case ADVENTURE -> mode(viewer, SpectatorMode.Mode.GHOST);
            default -> Msg.fail(viewer, "You're spectating: ", Msg.run("/spec mode true"), " or ",
                    Msg.run("/spec mode ghost"), ", or ", Msg.run("/l"), " first.");
        }
        return true;
    }
}
