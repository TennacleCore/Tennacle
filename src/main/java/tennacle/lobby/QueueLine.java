package tennacle.lobby;

import io.github.term4.canvas.Canvas;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.canvas.theme.Theme;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.timer.TaskSchedule;
import tennacle.games.matchmaking.Queues;

/** Every second a side waits: where it waits and for how long, on the action bar, and on the menu it waits from. */
public final class QueueLine {

    private QueueLine() {}

    public static void install() {
        MinecraftServer.getSchedulerManager().buildTask(QueueLine::beat).repeat(TaskSchedule.seconds(1)).schedule();
    }

    private static void beat() {
        for (Player player : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            Queues.Waiting waiting = Queues.of(player);
            if (waiting == null) continue;
            Theme theme = Canvas.themeFor(player);
            long seconds = Math.max(0, (System.currentTimeMillis() - waiting.since()) / 1000);
            player.sendActionBar(theme.prose("Queued \u00b7 ").append(theme.value(waiting.label()))
                    .append(theme.prose(" \u00b7 " + seconds / 60 + ":" + String.format("%02d", seconds % 60) + " \u00b7 "))
                    .append(theme.accent("/lobby")).append(theme.prose(" to leave")));
            Screen open = Screens.screen(player);
            if (open != null && Counts.KEY.equals(open.key())) Screens.repaint(player);
        }
    }
}
