package tennacle.postgres;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tennacle.storage.ReplayBlobs;
import tennacle.storage.ReplayStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The index here, the bytes in {@code blobs}. A replay is everyone's for {@code keepMillis} (0 = forever); after
 * that, a bookmark keeps it for the player who made it, a staff hold for staff, and with neither its bytes go.
 */
public final class PgReplays implements ReplayStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(PgReplays.class);
    private static final UUID STAFF = new UUID(0, 0);
    private static final String BOOKMARK = "bookmark";
    private static final String HOLD = "staff";

    private final Database db;
    private final ReplayBlobs blobs;
    private final long keepMillis;
    private final LongSupplier clock;

    public PgReplays(@NotNull Database db, @NotNull ReplayBlobs blobs, long keepMillis) {
        this(db, blobs, keepMillis, System::currentTimeMillis);
    }

    PgReplays(Database db, ReplayBlobs blobs, long keepMillis, LongSupplier clock) {
        this.db = db;
        this.blobs = blobs;
        this.keepMillis = keepMillis;
        this.clock = clock;
    }

    public long keepMillis() {
        return keepMillis;
    }

    // bytes first: a row never points at nothing
    @Override
    public @NotNull CompletableFuture<Void> put(@NotNull Entry entry, byte @NotNull [] bytes) {
        String key = ReplayBlobs.key(entry.mode(), entry.id());
        return blobs.put(key, bytes).thenCompose(stored -> db.query(c -> {
            insert(c, entry, key);
            return null;
        }));
    }

    @Override
    public @NotNull CompletableFuture<byte @Nullable []> bytes(@NotNull String id, @NotNull UUID viewer, boolean staff) {
        long now = clock.getAsLong();
        return db.query(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT r.blob FROM replays r WHERE r.id = ? AND r.expired_at = 0 "
                    + "AND (? OR r.played_at >= ? OR EXISTS (SELECT 1 FROM replay_holds h WHERE h.replay_id = r.id AND "
                    + "((h.kind = '" + BOOKMARK + "' AND h.holder = ?) OR (? AND h.kind = '" + HOLD + "' AND h.until >= ?))))")) {
                s.setString(1, id);
                s.setBoolean(2, keepMillis <= 0);
                s.setLong(3, now - keepMillis);
                s.setObject(4, viewer);
                s.setBoolean(5, staff);
                s.setLong(6, now);
                try (ResultSet rows = s.executeQuery()) {
                    return rows.next() ? rows.getString(1) : null;
                }
            }
        }).thenCompose(key -> key == null ? CompletableFuture.completedFuture(null) : blobs.get(key));
    }

    @Override
    public @NotNull CompletableFuture<Void> hold(@NotNull String id, long until) {
        return db.query(c -> {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO replay_holds (replay_id, kind, holder, until, at) "
                    + "SELECT id, '" + HOLD + "', ?, ?, ? FROM replays WHERE id = ? AND expired_at = 0 "
                    + "ON CONFLICT (replay_id, kind, holder) DO UPDATE SET until = greatest(replay_holds.until, excluded.until)")) {
                s.setObject(1, STAFF);
                s.setLong(2, until);
                s.setLong(3, clock.getAsLong());
                s.setString(4, id);
                s.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public @NotNull CompletableFuture<Bookmark> bookmark(@NotNull UUID player, @NotNull String id) {
        long now = clock.getAsLong();
        return db.transaction(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "DELETE FROM replay_holds WHERE replay_id = ? AND kind = '" + BOOKMARK + "' AND holder = ?")) {
                s.setString(1, id);
                s.setObject(2, player);
                if (s.executeUpdate() > 0) return Bookmark.REMOVED;
            }
            // a bookmark keeps a replay; it can't bring back one already past its keep
            if (!everyones(c, id, now)) return Bookmark.MISSING;
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT count(*) FROM replay_holds WHERE holder = ? AND kind = '" + BOOKMARK + "'")) {
                s.setObject(1, player);
                try (ResultSet rows = s.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) >= BOOKMARKS) return Bookmark.FULL;
                }
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO replay_holds (replay_id, kind, holder, until, at) "
                    + "VALUES (?, '" + BOOKMARK + "', ?, ?, ?)")) {
                s.setString(1, id);
                s.setObject(2, player);
                s.setLong(3, Long.MAX_VALUE);
                s.setLong(4, now);
                s.executeUpdate();
            }
            return Bookmark.ADDED;
        });
    }

    private boolean everyones(Connection c, String id, long now) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM replays WHERE id = ? AND expired_at = 0 AND (? OR played_at >= ?)")) {
            s.setString(1, id);
            s.setBoolean(2, keepMillis <= 0);
            s.setLong(3, now - keepMillis);
            try (ResultSet rows = s.executeQuery()) {
                return rows.next();
            }
        }
    }

    /** Indexes the replays already in the blob store, on the first boot against this database only. */
    public @NotNull CompletableFuture<Integer> adoptOnce(@NotNull Supplier<Collection<Entry>> stored) {
        return db.transaction(c -> {
            try (PreparedStatement mark = c.prepareStatement("INSERT INTO imports (name) VALUES ('replays-v1') ON CONFLICT DO NOTHING")) {
                if (mark.executeUpdate() == 0) return 0;
            }
            int added = 0;
            for (Entry entry : stored.get()) if (insert(c, entry, ReplayBlobs.key(entry.mode(), entry.id()))) added++;
            if (added > 0) LOGGER.info("indexed {} replays already on disk", added);
            return added;
        });
    }

    /** Lets go of the bytes nothing keeps any more; the rows stay, so match history outlives them. */
    public @NotNull CompletableFuture<Integer> expire(long now) {
        if (keepMillis <= 0) return CompletableFuture.completedFuture(0);
        return db.query(c -> {
            List<String[]> due = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement("SELECT r.id, r.blob FROM replays r WHERE r.expired_at = 0 "
                    + "AND r.played_at < ? AND NOT EXISTS (SELECT 1 FROM replay_holds h WHERE h.replay_id = r.id "
                    + "AND (h.kind = '" + BOOKMARK + "' OR h.until >= ?)) LIMIT 500")) {
                s.setLong(1, now - keepMillis);
                s.setLong(2, now);
                try (ResultSet rows = s.executeQuery()) {
                    while (rows.next()) due.add(new String[]{rows.getString(1), rows.getString(2)});
                }
            }
            return due;
        }).thenCompose(due -> {
            CompletableFuture<?>[] gone = due.stream().map(row -> blobs.delete(row[1]).thenCompose(deleted -> db.query(c -> {
                try (PreparedStatement s = c.prepareStatement("UPDATE replays SET expired_at = ? WHERE id = ?")) {
                    s.setLong(1, now);
                    s.setString(2, row[0]);
                    s.executeUpdate();
                }
                return null;
            }))).toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(gone).thenApply(done -> {
                if (!due.isEmpty()) LOGGER.info("expired {} replays", due.size());
                return due.size();
            });
        });
    }

    private static boolean insert(Connection c, Entry entry, String key) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO replays (id, code, mode, map, played_at, ticks, size, "
                + "blob) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
            s.setString(1, entry.id());
            s.setString(2, entry.code());
            s.setString(3, entry.mode());
            s.setString(4, entry.map());
            s.setLong(5, entry.playedAt());
            s.setLong(6, entry.ticks());
            s.setLong(7, entry.size());
            s.setString(8, key);
            return s.executeUpdate() > 0;
        }
    }
}
