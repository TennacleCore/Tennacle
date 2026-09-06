package tennacle.storage;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/** Durable writes: a temp sibling, then a move, so a crash or a full disk mid-write never truncates the target. */
public final class Atomic {

    private static final Logger LOGGER = LoggerFactory.getLogger(Atomic.class);

    private Atomic() {}

    /** Drops the temp siblings a crash mid-{@link #write} left anywhere under {@code root}; how many went. */
    public static int sweep(@NotNull Path root) {
        if (!Files.isDirectory(root)) return 0;
        int dropped = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".tmp")).toList()) {
                Files.deleteIfExists(file);
                dropped++;
            }
        } catch (IOException e) {
            LOGGER.warn("{} was not swept of temp files", root, e);
        }
        if (dropped > 0) LOGGER.info("dropped {} temp file(s) a crash left under {}", dropped, root);
        return dropped;
    }

    public static void write(@NotNull Path target, byte @NotNull [] bytes) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) { // some network/container filesystems
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
