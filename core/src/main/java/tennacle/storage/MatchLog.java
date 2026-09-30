package tennacle.storage;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * One durable line per finished match, appended to {@code <dir>/<date>.jsonl}: who played, what they played,
 * how long it ran and how it ended. Grotto keeps the last few results in memory for the end screen; nothing
 * outlived the process before this.
 *
 * <p>The roster is captured at the start because a finished game has already released its seats.
 */
public final class MatchLog {

    private static final Logger LOGGER = LoggerFactory.getLogger(MatchLog.class);

    private record Started(long at, String mode, String platform, List<List<String>> sides) {}

    private static final Map<String, Started> live = new ConcurrentHashMap<>();
    private static volatile Path dir;
    private static volatile ExecutorService io;

    private MatchLog() {}

    public static void install(@NotNull Path directory) {
        dir = directory;
        io = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "tennacle-matchlog");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static void started(@NotNull Game game, @NotNull String mode, @NotNull String platform,
                               @NotNull List<? extends Collection<Player>> sides) {
        if (dir == null) return;
        live.put(game.id(), new Started(System.currentTimeMillis(), mode, platform,
                sides.stream().map(side -> side.stream().map(Player::getUsername).toList()).toList()));
    }

    public static void ended(@NotNull Game game, @NotNull Outcome outcome) {
        Started start = live.remove(game.id());
        if (dir == null || start == null) return;
        long now = System.currentTimeMillis();
        StringBuilder line = new StringBuilder(256);
        line.append('{');
        field(line, "id", game.id()).append(',');
        field(line, "code", game.code()).append(',');
        field(line, "mode", start.mode()).append(',');
        field(line, "platform", start.platform()).append(',');
        field(line, "outcome", outcome.kind()).append(',');
        field(line, "winner", outcome.winner() == null ? "" : outcome.winner()).append(',');
        line.append("\"startedAt\":").append(start.at()).append(',');
        line.append("\"endedAt\":").append(now).append(',');
        line.append("\"seconds\":").append((now - start.at()) / 1000L).append(',');
        line.append("\"ticks\":").append(game.state().tick()).append(',');
        line.append("\"sides\":[");
        for (int i = 0; i < start.sides().size(); i++) {
            if (i > 0) line.append(',');
            line.append('[');
            List<String> side = start.sides().get(i);
            for (int j = 0; j < side.size(); j++) {
                if (j > 0) line.append(',');
                line.append('"').append(escape(side.get(j))).append('"');
            }
            line.append(']');
        }
        line.append("]}").append('\n');
        append(line.toString());
    }

    /** Waits out the pending appends; {@code false} = something was still in flight. */
    public static boolean flush(long timeout, @NotNull TimeUnit unit) {
        ExecutorService pool = io;
        if (pool == null) return true;
        pool.shutdown();
        try {
            return pool.awaitTermination(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void append(String line) {
        ExecutorService pool = io;
        Path directory = dir;
        if (pool == null || directory == null || pool.isShutdown()) return;
        pool.execute(() -> {
            Path file = directory.resolve(LocalDate.now() + ".jsonl");
            try {
                Files.createDirectories(directory);
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                LOGGER.error("could not record the match in {}", file.getFileName(), e);
            }
        });
    }

    private static StringBuilder field(StringBuilder out, String name, String value) {
        return out.append('"').append(name).append("\":\"").append(escape(value)).append('"');
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
