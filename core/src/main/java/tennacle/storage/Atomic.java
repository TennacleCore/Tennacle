package tennacle.storage;

import io.github.term4.archipelago.storage.Durable;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** The app's side of durable storage: {@link Durable#write} plus the sweep of what a crash left behind. */
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
        Durable.write(target, bytes);
    }
}
