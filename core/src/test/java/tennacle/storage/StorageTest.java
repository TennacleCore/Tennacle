package tennacle.storage;

import io.github.term4.grotto.vault.VaultKey;
import net.minestom.server.codec.Codec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The app's storage: account data that outlives the process, writes that cannot half-land, names that stay put. */
class StorageTest {

    private static final VaultKey<String> NAME = VaultKey.of("test:name", Codec.STRING, "");
    private static final VaultKey<Integer> WINS = VaultKey.of("test:wins", Codec.INT, 0);

    @Test
    void accountDataSurvivesTheProcess(@TempDir Path dir) throws Exception {
        UUID player = UUID.randomUUID();
        FileVault first = new FileVault(dir);
        try {
            assertEquals("", first.get(player, NAME).get(5, TimeUnit.SECONDS), "an unknown account is the fallback");
            first.set(player, NAME, "Term4").get(5, TimeUnit.SECONDS);
            first.set(player, WINS, 3).get(5, TimeUnit.SECONDS);
            assertTrue(first.flush(5, TimeUnit.SECONDS));
        } finally {
            first.close();
        }

        FileVault second = new FileVault(dir); // a restart
        try {
            assertEquals("Term4", second.get(player, NAME).get(5, TimeUnit.SECONDS));
            assertEquals(3, second.get(player, WINS).get(5, TimeUnit.SECONDS), "every key in the one file");
            assertEquals(4, second.update(player, WINS, wins -> wins + 1).get(5, TimeUnit.SECONDS));
            assertTrue(second.flush(5, TimeUnit.SECONDS));
            second.forget(player);
            assertEquals(4, second.get(player, WINS).get(5, TimeUnit.SECONDS), "forgotten means re-read, not lost");
        } finally {
            second.close();
        }
    }

    @Test
    void aWriteNeverHalfLands(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("nested").resolve("thing.dat");
        Atomic.write(target, "first".getBytes());
        assertEquals("first", Files.readString(target));
        Atomic.write(target, "second".getBytes());
        assertEquals("second", Files.readString(target));
        try (var listing = Files.list(target.getParent())) {
            assertEquals(1, listing.count(), "no temp file left behind");
        }
    }

    @Test
    void aNameFromACommandStaysInItsDirectory(@TempDir Path dir) {
        assertNotNull(SafeName.resolve(dir, "urban", ".bncl"));
        assertNotNull(SafeName.resolve(dir, "map-2.old", ".bncl"));
        assertNull(SafeName.resolve(dir, "../../etc/passwd", ".bncl"), "no escaping the directory");
        assertNull(SafeName.resolve(dir, "..", ".bncl"));
        assertNull(SafeName.resolve(dir, ".hidden", ".bncl"), "no leading dot");
        assertNull(SafeName.resolve(dir, "a/b", ".bncl"));
        assertNull(SafeName.resolve(dir, "", ".bncl"));
        assertFalse(SafeName.valid("x".repeat(65)));
        assertTrue(SafeName.valid("x".repeat(64)));
    }
}
