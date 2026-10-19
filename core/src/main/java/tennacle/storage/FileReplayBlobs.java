package tennacle.storage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Each key a file under {@code root}. */
public final class FileReplayBlobs implements ReplayBlobs {

    private final Path root;
    private final ExecutorService io = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tennacle-replay-io-", 0).factory());

    public FileReplayBlobs(@NotNull Path root) {
        this.root = root;
    }

    @Override
    public @NotNull CompletableFuture<Void> put(@NotNull String key, byte @NotNull [] bytes) {
        return CompletableFuture.runAsync(() -> {
            try {
                Atomic.write(file(key), bytes);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, io);
    }

    @Override
    public @NotNull CompletableFuture<byte @Nullable []> get(@NotNull String key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return Files.readAllBytes(file(key));
            } catch (NoSuchFileException gone) {
                return null;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, io);
    }

    @Override
    public @NotNull CompletableFuture<Void> delete(@NotNull String key) {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.deleteIfExists(file(key));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, io);
    }

    private Path file(String key) {
        Path file = root.resolve(key).normalize();
        if (!file.startsWith(root.normalize())) throw new IllegalArgumentException("key outside the replay folder: " + key);
        return file;
    }
}
