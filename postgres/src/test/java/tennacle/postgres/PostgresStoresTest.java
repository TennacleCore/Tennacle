package tennacle.postgres;

import io.github.term4.grotto.vault.VaultKey;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import net.minestom.server.codec.Codec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tennacle.moderation.Punishments;
import tennacle.moderation.Punishments.Kind;
import tennacle.storage.FileVault;
import tennacle.storage.MatchStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresStoresTest {

    private static final VaultKey<Integer> COUNT = VaultKey.of("test:count", Codec.INT, 0);

    private static EmbeddedPostgres server;
    private static Database db;

    @BeforeAll
    static void start() throws Exception {
        server = EmbeddedPostgres.start();
        db = Database.connect(server.getJdbcUrl("postgres", "postgres"), "postgres", null, 8);
    }

    @AfterAll
    static void stop() throws Exception {
        db.close();
        server.close();
    }

    @Test
    void vaultUpdatesNeverLose() {
        PgVault vault = new PgVault(db);
        UUID player = UUID.randomUUID();
        assertEquals(0, vault.get(player, COUNT).join(), "absent reads the fallback");
        CompletableFuture<?>[] bumps = IntStream.range(0, 40)
                .mapToObj(i -> vault.update(player, COUNT, n -> n + 1)).toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(bumps).join();
        assertEquals(40, vault.get(player, COUNT).join());
        vault.set(player, COUNT, 7).join();
        assertEquals(7, vault.get(player, COUNT).join());
    }

    @Test
    void directoryKnowsNewestName() {
        PgDirectory directory = new PgDirectory(db);
        UUID old = UUID.randomUUID();
        UUID now = UUID.randomUUID();
        directory.seen(old, "Taken", 1_000).join();
        directory.seen(now, "taken", 2_000).join();
        assertEquals(now, directory.byName("TAKEN").join().id(), "whoever used it last");
        directory.seen(old, "Renamed", 3_000).join();
        assertEquals("Renamed", directory.byId(old).join().name());
        assertEquals(Set.of(old, now), directory.byIds(List.of(old, now, UUID.randomUUID())).join().keySet());
        assertNull(directory.byName("nobody").join());
    }

    @Test
    void punishmentsLiftAndList() {
        PgPunishments store = new PgPunishments(db);
        UUID player = UUID.randomUUID();
        store.add(player, Kind.MUTE, "spam", null, "Console", 1_000, 5_000).join();
        store.add(player, Kind.BAN, "", UUID.randomUUID(), "Mod", 2_000, Punishments.FOREVER).join();
        assertEquals("spam", store.active(player, Kind.MUTE, 3_000).join().reason());
        assertNull(store.active(player, Kind.MUTE, 6_000).join(), "ran out");
        assertTrue(store.active(player, Kind.BAN, Long.MAX_VALUE - 1).join().permanent());

        UUID admin = UUID.randomUUID();
        Punishments.Punishment lifted = store.lift(player, Kind.BAN, admin, "Admin", 4_000).join();
        assertNotNull(lifted);
        assertNull(store.active(player, Kind.BAN, 4_001).join());
        assertNull(store.lift(player, Kind.BAN, admin, "Admin", 4_002).join(), "nothing left to lift");

        List<Punishments.Punishment> history = store.history(player).join();
        assertEquals(List.of(Kind.BAN, Kind.MUTE), history.stream().map(Punishments.Punishment::kind).toList());
        assertEquals("Admin", history.getFirst().liftedByName());
        assertEquals(admin, history.getFirst().liftedBy());
    }

    @Test
    void importCopiesFilesOnce(@TempDir Path data) throws Exception {
        UUID term = UUID.randomUUID();
        UUID dog = UUID.randomUUID();
        FileVault files = new FileVault(data.resolve("players"));
        files.set(term, COUNT, 12).join();
        assertTrue(files.flush(5, TimeUnit.SECONDS));
        files.close();
        Files.writeString(data.resolve("directory.tsv"), term + "\tImpTerm\t100\n" + dog + "\tImpDog\t50\n" + term + "\tImpTerm\t90\n");
        Files.createDirectories(data.resolve("matches"));
        String id = UUID.randomUUID().toString();
        String fresh = UUID.randomUUID().toString();
        Files.writeString(data.resolve("matches").resolve("2026-09-04.jsonl"),
                "{\"id\":\"" + id + "\",\"code\":\"abcde\",\"mode\":\"bridge\",\"platform\":\"scrims\",\"outcome\":\"winner\","
                        + "\"winner\":\"1\",\"startedAt\":1000,\"endedAt\":31000,\"seconds\":30,\"ticks\":600,"
                        + "\"sides\":[[\"impdog\"],[\"Stranger\"]]}\n{torn\n"
                        + "{\"id\":\"" + fresh + "\",\"code\":\"fghij\",\"mode\":\"sumo\",\"platform\":\"hypixel\",\"outcome\":\"draw\","
                        + "\"winner\":\"\",\"startedAt\":1000,\"endedAt\":2000,\"seconds\":1,\"ticks\":20,"
                        + "\"sides\":[[{\"id\":\"" + term + "\",\"name\":\"ImpTerm\"}]]}\n", StandardCharsets.UTF_8);

        Imports.filesOnce(db, data);
        assertEquals(12, new PgVault(db).get(term, COUNT).join());
        assertEquals(100, new PgDirectory(db).byId(term).join().lastSeen(), "the newest line wins");
        Map<String, Object> seats = db.query(c -> {
            Map<String, Object> out = new java.util.HashMap<>();
            try (var rows = c.createStatement().executeQuery("SELECT name, player FROM match_players WHERE match_id IN ('"
                    + id + "', '" + fresh + "')")) {
                while (rows.next()) out.put(rows.getString(1), rows.getObject(2) == null ? "none" : rows.getObject(2));
            }
            return out;
        }).join();
        assertEquals(Map.of("impdog", dog, "Stranger", "none", "ImpTerm", term), seats);

        new PgVault(db).set(term, COUNT, 13).join();
        Imports.filesOnce(db, data);
        assertEquals(13, new PgVault(db).get(term, COUNT).join(), "a second boot imports nothing");
    }

    @Test
    void matchesKeepEverySeat() {
        PgMatches matches = new PgMatches(db);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        String id = UUID.randomUUID().toString();
        matches.add(new MatchStore.Match(id, "code1", "bridge", "scrims", "winner", "1", 1_000, 2_000, 40,
                List.of(List.of(new MatchStore.Seat(a, "A")), List.of(new MatchStore.Seat(b, "B"))))).join();
        long seated = db.query(c -> {
            try (var rows = c.createStatement().executeQuery("SELECT count(*) FROM match_players WHERE match_id = '" + id + "'")) {
                rows.next();
                return rows.getLong(1);
            }
        }).join();
        assertEquals(2, seated);
    }
}
