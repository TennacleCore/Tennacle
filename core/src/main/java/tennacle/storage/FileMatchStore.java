package tennacle.storage;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** One line per match, appended to {@code <dir>/<date>.jsonl}. */
public final class FileMatchStore implements MatchStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileMatchStore.class);

    private final Path dir;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "tennacle-matchlog");
        thread.setDaemon(true);
        return thread;
    });

    public FileMatchStore(@NotNull Path dir) {
        this.dir = dir;
    }

    @Override
    public @NotNull CompletableFuture<Void> add(@NotNull Match match) {
        String line = line(match);
        return CompletableFuture.runAsync(() -> {
            Path file = dir.resolve(LocalDate.now() + ".jsonl");
            try {
                Files.createDirectories(dir);
                Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                LOGGER.error("could not record the match in {}", file.getFileName(), e);
                throw new UncheckedIOException(e);
            }
        }, io);
    }

    @Override
    public boolean flush(long timeout, @NotNull TimeUnit unit) {
        io.shutdown();
        try {
            return io.awaitTermination(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static String line(Match match) {
        StringBuilder line = new StringBuilder(256).append('{');
        field(line, "id", match.id()).append(',');
        field(line, "code", match.code()).append(',');
        field(line, "mode", match.mode()).append(',');
        field(line, "platform", match.platform()).append(',');
        field(line, "outcome", match.outcome()).append(',');
        field(line, "winner", match.winner() == null ? "" : match.winner()).append(',');
        line.append("\"startedAt\":").append(match.startedAt()).append(',');
        line.append("\"endedAt\":").append(match.endedAt()).append(',');
        line.append("\"seconds\":").append((match.endedAt() - match.startedAt()) / 1000L).append(',');
        line.append("\"ticks\":").append(match.ticks()).append(',');
        line.append("\"sides\":[");
        for (int i = 0; i < match.sides().size(); i++) {
            if (i > 0) line.append(',');
            line.append('[');
            List<Seat> side = match.sides().get(i);
            for (int j = 0; j < side.size(); j++) {
                if (j > 0) line.append(',');
                line.append('{');
                field(line, "id", side.get(j).id().toString()).append(',');
                field(line, "name", side.get(j).name()).append('}');
            }
            line.append(']');
        }
        return line.append("]}\n").toString();
    }

    private static StringBuilder field(StringBuilder out, String name, String value) {
        return out.append('"').append(name).append("\":\"").append(escape(value)).append('"');
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
