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
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** The index here, the bytes in {@code blobs}. A replay keeps for {@code keepMillis} (0 = forever) unless something holds it longer. */
public final class PgReplays implements ReplayStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(PgReplays.class);

    private final Database db;
    private final ReplayBlobs blobs;
    private final long keepMillis;

    public PgReplays(@NotNull Database db, @NotNull ReplayBlobs blobs, long keepMillis) {
        this.db = db;
        this.blobs = blobs;
        this.keepMillis = keepMillis;
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
    public @NotNull CompletableFuture<byte @Nullable []> bytes(@NotNull String id) {
        return db.query(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT blob FROM replays WHERE id = ? AND expired_at = 0")) {
                s.setString(1, id);
                try (ResultSet rows = s.executeQuery()) {
                    return rows.next() ? rows.getString(1) : null;
                }
            }
        }).thenCompose(key -> key == null ? CompletableFuture.completedFuture(null) : blobs.get(key));
    }

    @Override
    public @NotNull CompletableFuture<Void> keep(@NotNull String id, long until) {
        return db.query(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "UPDATE replays SET keep_until = greatest(keep_until, ?) WHERE id = ? AND expired_at = 0")) {
                s.setLong(1, until);
                s.setString(2, id);
                s.executeUpdate();
            }
            return null;
        });
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

    /** Lets go of the bytes of every replay past its keep and holds; the rows stay, so match history outlives them. */
    public @NotNull CompletableFuture<Integer> expire(long now) {
        if (keepMillis <= 0) return CompletableFuture.completedFuture(0);
        return db.query(c -> {
            List<String[]> due = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement("SELECT id, blob FROM replays WHERE expired_at = 0 "
                    + "AND played_at < ? AND keep_until < ? LIMIT 500")) {
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

    private boolean insert(Connection c, Entry entry, String key) throws SQLException {
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
