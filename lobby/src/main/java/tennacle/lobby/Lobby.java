package tennacle.lobby;

import tennacle.games.Matches;
import tennacle.looks.Frames;

import io.github.term4.canvas.board.Board;
import io.github.term4.canvas.board.Boards;
import net.minestom.server.MinecraftServer;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.timer.TaskSchedule;

/**
 * The lobby sidebar: scrims' lobby shape in the house frame - winstreaks, online, playing - offered at priority
 * 0, so any game board sits on top of it and this reappears at the exit. The app owns the cadence: one coarse
 * timer touches it.
 */
public final class Lobby {

    private Lobby() {}

    public static void install() {
        Board board = Frames.tennacleLobby(b -> b
                .line(viewer -> tennacle.Msg.THEME.dim(where(viewer)))
                .blank()
                .line(viewer -> Frames.row("Online: ", MinecraftServer.getConnectionManager().getOnlinePlayerCount()))
                .line(viewer -> Frames.row("Playing: ", playing()))
                .blank()
                .line(viewer -> Frames.row("Party: ", party(viewer))));
        MinecraftServer.getGlobalEventHandler().addListener(PlayerSpawnEvent.class, event -> {
            if (event.isFirstSpawn()) Boards.show(event.getPlayer(), board, 0);
        });
        MinecraftServer.getSchedulerManager().buildTask(() -> Boards.touchAll(board))
                .repeat(TaskSchedule.tick(20)).schedule();
    }

    private static String where(net.minestom.server.entity.Player viewer) {
        Lobbies.Lobby lobby = Lobbies.in(viewer);
        if (lobby == null) return "";
        return lobby == Lobbies.main() ? lobby.name() : lobby.name() + " Lobby";
    }

    private static Object party(net.minestom.server.entity.Player viewer) {
        int size = tennacle.social.party.Parties.members(viewer).size();
        return size > 1 ? size : "None";
    }

    private static int playing() {
        int count = 0;
        for (var game : Matches.grotto().games()) count += game.memberCount();
        return count;
    }
}
