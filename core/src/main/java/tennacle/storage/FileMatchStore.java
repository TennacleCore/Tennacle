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
    public @NotNull CompletableFuture<Void> stats(@NotNull String match, @NotNull List<Line> lines, @NotNull List<Pair> pairs) {
        String line = statsLine(match, lines, pairs);
        return CompletableFuture.runAsync(() -> {
            Path file = dir.resolve(LocalDate.now() + "-stats.jsonl");
            try {
                Files.createDirectories(dir);
                Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                LOGGER.error("could not record the stats of match {} in {}", match, file.getFileName(), e);
                throw new UncheckedIOException(e);
            }
        }, io);
    }

    static String statsLine(String match, List<Line> lines, List<Pair> pairs) {
        StringBuilder out = new StringBuilder(64 + lines.size() * 48).append('{');
        field(out, "match", match).append(",\"lines\":[");
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (i > 0) out.append(',');
            out.append('{');
            field(out, "player", line.player().toString()).append(',');
            field(out, "stat", line.stat()).append(",\"value\":").append(line.value()).append('}');
        }
        out.append("],\"pairs\":[");
        for (int i = 0; i < pairs.size(); i++) {
            Pair pair = pairs.get(i);
            if (i > 0) out.append(',');
            out.append('{');
            field(out, "player", pair.player().toString()).append(',');
            field(out, "other", pair.other()).append(',');
            field(out, "stat", pair.stat()).append(",\"value\":").append(pair.value()).append('}');
        }
        return out.append("]}\n").toString();
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
        field(line, "map", match.map()).append(',');
        field(line, "outcome", match.outcome()).append(',');
        field(line, "winner", match.winner() == null ? "" : match.winner()).append(',');
        line.append("\"startedAt\":").append(match.startedAt()).append(',');
        line.append("\"endedAt\":").append(match.endedAt()).append(',');
        line.append("\"seconds\":").append((match.endedAt() - match.startedAt()) / 1000L).append(',');
        line.append("\"ticks\":").append(match.ticks()).append(',');
        line.append("\"sides\":[");
        for (int i = 0; i < match.sides().size(); i++) {
            if (i > 0) line.append(',');
            Side side = match.sides().get(i);
            line.append('{');
            field(line, "team", side.team()).append(',');
            field(line, "name", side.name()).append(',');
            field(line, "color", side.color()).append(",\"seats\":[");
            List<Seat> seats = side.seats();
            for (int j = 0; j < seats.size(); j++) {
                if (j > 0) line.append(',');
                line.append('{');
                field(line, "id", seats.get(j).id().toString()).append(',');
                field(line, "name", seats.get(j).name()).append('}');
            }
            line.append("]}");
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
