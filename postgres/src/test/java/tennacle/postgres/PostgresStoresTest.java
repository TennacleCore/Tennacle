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
import tennacle.storage.FileReplayBlobs;
import tennacle.storage.FileVault;
import tennacle.storage.MatchStore;
import tennacle.storage.ReplayStore;
import tennacle.storage.StatKeys;
import tennacle.storage.StatRouting;
import tennacle.storage.StatStore;

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
    void statsMoveAndRank() {
        VaultKey<Integer> kills = VaultKey.of("test:career-kills", Codec.INT, 0);
        PgVault blobs = new PgVault(db);
        PgStats stats = new PgStats(db);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        blobs.set(a, kills, 5).join();
        blobs.set(b, kills, 9).join();

        StatKeys.register(kills);
        stats.adopt(StatKeys.all()).join();
        StatRouting vault = new StatRouting(blobs, stats);
        assertEquals(5, vault.get(a, kills).join(), "moved out of the blob");
        assertEquals(0, blobs.get(a, kills).join(), "and the blob is gone");

        CompletableFuture<?>[] bumps = IntStream.range(0, 10)
                .mapToObj(i -> vault.update(a, kills, n -> n + 1)).toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(bumps).join();
        assertEquals(List.of(new StatStore.Standing(a, 15), new StatStore.Standing(b, 9)), stats.top(kills.id(), 2).join());
        vault.set(b, COUNT, 3).join();
        assertEquals(3, blobs.get(b, COUNT).join(), "other keys stay account data");

        blobs.set(a, kills, 100).join();
        stats.adopt(StatKeys.all()).join();
        assertEquals(15, vault.get(a, kills).join(), "a key moves once");
    }

    @Test
    void replaysKeepThenExpire(@TempDir Path root) {
        PgReplays replays = new PgReplays(db, new FileReplayBlobs(root), 1_000);
        String kept = UUID.randomUUID().toString();
        String held = UUID.randomUUID().toString();
        byte[] bytes = {1, 2, 3};
        replays.put(new ReplayStore.Entry(kept, "AAAAA", "bridge", "Urban", 10_000, 600, 3), bytes).join();
        replays.put(new ReplayStore.Entry(held, "BBBBB", "sumo", "Ring", 10_000, 20, 3), bytes).join();
        assertTrue(Files.exists(root.resolve("bridge").resolve(kept + ".shard")), "the bytes in the blob store");
        assertTrue(java.util.Arrays.equals(bytes, replays.bytes(kept).join()));
        assertNull(replays.bytes(UUID.randomUUID().toString()).join());

        replays.keep(held, 50_000).join();
        assertTrue(replays.expire(20_000).join() >= 1);
        assertNull(replays.bytes(kept).join(), "past its keep");
        assertTrue(!Files.exists(root.resolve("bridge").resolve(kept + ".shard")), "and its bytes are gone");
        assertTrue(java.util.Arrays.equals(bytes, replays.bytes(held).join()), "a hold outlasts the keep");

        String old = UUID.randomUUID().toString();
        assertEquals(1, replays.adoptOnce(() -> List.of(new ReplayStore.Entry(old, "CCCCC", "boxing", "Arena", 9_000_000_000_000L, 0, 3))).join());
        assertEquals(0, replays.adoptOnce(() -> { throw new AssertionError("not read twice"); }).join());
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
