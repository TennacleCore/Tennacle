package tennacle.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectoryTest {

    @Test
    void namesSurviveARestart(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("directory.tsv");
        UUID term = UUID.randomUUID();
        FileDirectory first = new FileDirectory(file);
        first.seen(term, "Term4", 100);
        first.seen(term, "Term4", 200);
        first.seen(term, "Term5", 300);
        assertTrue(first.flush(5, TimeUnit.SECONDS));
        first.close();
        assertEquals(3, Files.readAllLines(file).size(), "a line per stamp");

        FileDirectory second = new FileDirectory(file);
        Directory.Entry entry = second.byName("term5").get(5, TimeUnit.SECONDS);
        assertEquals(new Directory.Entry(term, "Term5", 300), entry, "any case, the newest line");
        assertNull(second.byName("Term4").get(5, TimeUnit.SECONDS), "an old name no longer answers");
        assertEquals(1, Files.readAllLines(file).size(), "compacted at boot");
        second.close();
    }

    @Test
    void aTakenNameAnswersTheNewest() throws Exception {
        MemoryDirectory directory = new MemoryDirectory();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        directory.seen(first, "Dogln", 100);
        directory.seen(second, "Dogln", 200);
        assertEquals(second, directory.byName("dogln").get().id());
        directory.seen(first, "Renamed", 300);
        assertEquals(second, directory.byName("Dogln").get().id(), "the old owner moving on leaves it alone");
        assertEquals("Renamed", directory.byId(first).get().name());
    }
}
