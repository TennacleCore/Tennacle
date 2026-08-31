package tennacle.lobby;

import tennacle.games.Duels;

import io.github.term4.canvas.board.Board;
import io.github.term4.canvas.board.Boards;
import io.github.term4.canvas.Canvas;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.timer.TaskSchedule;

import java.time.LocalDate;

/**
 * The lobby sidebar: server filler (date, online, playing) offered at priority 0 - any game board sits
 * on top of it and this reappears at the exit. The app owns the cadence: one coarse timer touches it.
 */
public final class Lobby {

    private Lobby() {}

    public static void install() {
        Board board = Board.builder()
                .title(viewer -> Canvas.themeFor(viewer).boardTitle(viewer, "Tennacle"))
                .line(viewer -> Canvas.themeFor(viewer).dim(LocalDate.now().toString()))
                .blank()
                .line(viewer -> Component.text("Online: ", NamedTextColor.WHITE)
                        .append(Canvas.themeFor(viewer).good(MinecraftServer.getConnectionManager().getOnlinePlayerCount())))
                .line(viewer -> Component.text("Playing: ", NamedTextColor.WHITE)
                        .append(Canvas.themeFor(viewer).value(playing())))
                .blank()
                .line(viewer -> Canvas.themeFor(viewer).dim("tennacle"))
                .build();
        MinecraftServer.getGlobalEventHandler().addListener(PlayerSpawnEvent.class, event -> {
            if (event.isFirstSpawn()) Boards.show(event.getPlayer(), board, 0);
        });
        MinecraftServer.getSchedulerManager().buildTask(() -> Boards.touchAll(board))
                .repeat(TaskSchedule.tick(20)).schedule();
    }

    private static int playing() {
        int count = 0;
        for (var game : Duels.grotto().games()) count += game.members().size();
        return count;
    }
}
