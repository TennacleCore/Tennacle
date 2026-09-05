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
                .line(viewer -> Frames.row("Daily Winstreak: ", 0)) // filler until the vault tracks them
                .line(viewer -> Frames.row("Overall Winstreak: ", 0))
                .blank()
                .line(viewer -> Frames.row("Online: ", MinecraftServer.getConnectionManager().getOnlinePlayerCount()))
                .line(viewer -> Frames.row("Playing: ", playing())));
        MinecraftServer.getGlobalEventHandler().addListener(PlayerSpawnEvent.class, event -> {
            if (event.isFirstSpawn()) Boards.show(event.getPlayer(), board, 0);
        });
        MinecraftServer.getSchedulerManager().buildTask(() -> Boards.touchAll(board))
                .repeat(TaskSchedule.tick(20)).schedule();
    }

    private static int playing() {
        int count = 0;
        for (var game : Matches.grotto().games()) count += game.memberCount();
        return count;
    }
}
