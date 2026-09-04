package tennacle.storage;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Durable writes: a temp sibling, then a move, so a crash or a full disk mid-write never truncates the target. */
public final class Atomic {

    private Atomic() {}

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
