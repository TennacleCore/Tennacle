package tennacle.storage;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The {@link Directory} in one file, a line per join or leave ({@code uuid name lastSeen}, tab-separated). Read
 * whole at boot, where the newest line per player wins and the file is rewritten to one line each.
 */
public final class FileDirectory extends MemoryDirectory {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileDirectory.class);

    private final Path file;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "tennacle-directory");
        thread.setDaemon(true);
        return thread;
    });
    private volatile CompletableFuture<Void> pending = CompletableFuture.completedFuture(null);

    public FileDirectory(@NotNull Path file) {
        this.file = file;
        if (!Files.isReadable(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t");
                if (parts.length != 3) continue; // a line a crash cut short
                put(new Entry(UUID.fromString(parts[0]), parts[1], Long.parseLong(parts[2])));
            }
            StringBuilder compact = new StringBuilder();
            for (Entry entry : entries()) compact.append(line(entry));
            Atomic.write(file, compact.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("unreadable player directory " + file, e);
        }
    }

    @Override
    public synchronized @NotNull CompletableFuture<Void> seen(@NotNull UUID id, @NotNull String name, long at) {
        Entry entry = new Entry(id, name, at);
        put(entry);
        byte[] bytes = line(entry).getBytes(StandardCharsets.UTF_8);
        CompletableFuture<Void> write = pending.thenRunAsync(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                LOGGER.error("could not record {} in the player directory", name, e);
            }
        }, io);
        pending = write;
        return write;
    }

    /** Waits out every pending line; {@code false} = something was still in flight when the wait ran out. */
    public boolean flush(long timeout, @NotNull TimeUnit unit) {
        try {
            pending.get(timeout, unit);
            return true;
        } catch (Exception e) {
            LOGGER.warn("player directory did not finish writing", e);
            return false;
        }
    }

    public void close() {
        io.shutdown();
    }

    private static String line(Entry entry) {
        return entry.id() + "\t" + entry.name() + "\t" + entry.lastSeen() + "\n";
    }
}
