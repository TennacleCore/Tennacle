package tennacle.storage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/** No index: a replay is found by looking in every mode folder, and nothing expires. */
public final class FileReplays implements ReplayStore {

    private final Path root;
    private final FileReplayBlobs blobs;

    public FileReplays(@NotNull Path root) {
        this.root = root;
        this.blobs = new FileReplayBlobs(root);
    }

    @Override
    public @NotNull CompletableFuture<Void> put(@NotNull Entry entry, byte @NotNull [] bytes) {
        return blobs.put(ReplayBlobs.key(entry.mode(), entry.id()), bytes);
    }

    @Override
    public @NotNull CompletableFuture<byte @Nullable []> bytes(@NotNull String id, @NotNull UUID viewer, boolean staff) {
        String key = keyOf(id);
        return key == null ? CompletableFuture.completedFuture(null) : blobs.get(key);
    }

    // the first mode folder holding it
    private @Nullable String keyOf(String id) {
        String name = id + ".shard";
        try (Stream<Path> entries = Files.list(root)) {
            for (Path folder : entries.filter(Files::isDirectory).toList()) {
                if (Files.exists(folder.resolve(name))) return folder.getFileName() + "/" + name;
            }
        } catch (IOException nothingYet) {
            return null;
        }
        return null;
    }
}
