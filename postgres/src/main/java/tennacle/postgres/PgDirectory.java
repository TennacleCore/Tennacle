package tennacle.postgres;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.storage.Directory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PgDirectory implements Directory {

    private final Database db;

    public PgDirectory(@NotNull Database db) {
        this.db = db;
    }

    @Override
    public @NotNull CompletableFuture<@Nullable Entry> byId(@NotNull UUID id) {
        return db.query(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT id, name, last_seen FROM players WHERE id = ?")) {
                s.setObject(1, id);
                try (ResultSet rows = s.executeQuery()) {
                    return rows.next() ? entry(rows) : null;
                }
            }
        });
    }

    @Override
    public @NotNull CompletableFuture<@Nullable Entry> byName(@NotNull String name) {
        return db.query(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT id, name, last_seen FROM players WHERE lower(name) = lower(?) ORDER BY last_seen DESC LIMIT 1")) {
                s.setString(1, name);
                try (ResultSet rows = s.executeQuery()) {
                    return rows.next() ? entry(rows) : null;
                }
            }
        });
    }

    @Override
    public @NotNull CompletableFuture<Void> seen(@NotNull UUID id, @NotNull String name, long at) {
        return db.query(c -> {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO players (id, name, last_seen) VALUES (?, ?, ?) "
                    + "ON CONFLICT (id) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen")) {
                s.setObject(1, id);
                s.setString(2, name);
                s.setLong(3, at);
                s.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public @NotNull CompletableFuture<Map<UUID, Entry>> byIds(@NotNull Collection<UUID> ids) {
        if (ids.isEmpty()) return CompletableFuture.completedFuture(Map.of());
        return db.query(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT id, name, last_seen FROM players WHERE id = ANY (?)")) {
                s.setArray(1, c.createArrayOf("uuid", ids.toArray()));
                Map<UUID, Entry> found = new HashMap<>();
                try (ResultSet rows = s.executeQuery()) {
                    while (rows.next()) {
                        Entry entry = entry(rows);
                        found.put(entry.id(), entry);
                    }
                }
                return found;
            }
        });
    }

    private static Entry entry(ResultSet rows) throws SQLException {
        return new Entry(rows.getObject(1, UUID.class), rows.getString(2), rows.getLong(3));
    }
}
