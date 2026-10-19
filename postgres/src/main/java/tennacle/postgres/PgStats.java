package tennacle.postgres;

import io.github.term4.grotto.vault.VaultKey;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tennacle.storage.StatStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongUnaryOperator;

public final class PgStats implements StatStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(PgStats.class);

    private final Database db;

    public PgStats(@NotNull Database db) {
        this.db = db;
    }

    @Override
    public @NotNull CompletableFuture<Long> get(@NotNull UUID player, @NotNull String stat) {
        return db.query(c -> read(c, player, stat, false));
    }

    @Override
    public @NotNull CompletableFuture<Long> update(@NotNull UUID player, @NotNull String stat, @NotNull LongUnaryOperator fn) {
        return db.transaction(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO stats (player, stat, value) VALUES (?, ?, 0) ON CONFLICT DO NOTHING")) {
                s.setObject(1, player);
                s.setString(2, stat);
                s.executeUpdate();
            }
            long updated = fn.applyAsLong(read(c, player, stat, true));
            try (PreparedStatement s = c.prepareStatement("UPDATE stats SET value = ? WHERE player = ? AND stat = ?")) {
                s.setLong(1, updated);
                s.setObject(2, player);
                s.setString(3, stat);
                s.executeUpdate();
            }
            return updated;
        });
    }

    @Override
    public @NotNull CompletableFuture<List<Standing>> top(@NotNull String stat, int limit) {
        return db.query(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT player, value FROM stats WHERE stat = ? ORDER BY value DESC, player LIMIT ?")) {
                s.setString(1, stat);
                s.setInt(2, limit);
                List<Standing> out = new ArrayList<>();
                try (ResultSet rows = s.executeQuery()) {
                    while (rows.next()) out.add(new Standing(rows.getObject(1, UUID.class), rows.getLong(2)));
                }
                return out;
            }
        });
    }

    /** Moves the stats still kept as account blobs into rows, once per key. */
    public @NotNull CompletableFuture<Void> adopt(@NotNull Collection<VaultKey<Integer>> keys) {
        return db.transaction(c -> {
            int moved = 0;
            for (VaultKey<Integer> key : keys) {
                try (PreparedStatement mark = c.prepareStatement("INSERT INTO imports (name) VALUES (?) ON CONFLICT DO NOTHING")) {
                    mark.setString(1, "stat:" + key.id());
                    if (mark.executeUpdate() == 0) continue;
                }
                moved += move(c, key);
            }
            if (moved > 0) LOGGER.info("moved {} career numbers out of account data into stats", moved);
            return null;
        });
    }

    private static int move(Connection c, VaultKey<Integer> key) throws SQLException {
        int moved = 0;
        try (PreparedStatement read = c.prepareStatement("SELECT player, value FROM player_data WHERE key = ?");
             PreparedStatement write = c.prepareStatement("INSERT INTO stats (player, stat, value) VALUES (?, ?, ?) "
                     + "ON CONFLICT (player, stat) DO UPDATE SET value = stats.value + excluded.value")) {
            read.setString(1, key.id());
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    int value = key.decode(rows.getBytes(2));
                    if (value == 0) continue;
                    write.setObject(1, rows.getObject(1, UUID.class));
                    write.setString(2, key.id());
                    write.setLong(3, value);
                    write.addBatch();
                    moved++;
                }
            }
            write.executeBatch();
        }
        try (PreparedStatement drop = c.prepareStatement("DELETE FROM player_data WHERE key = ?")) {
            drop.setString(1, key.id());
            drop.executeUpdate();
        }
        return moved;
    }

    private static long read(Connection c, UUID player, String stat, boolean lock) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT value FROM stats WHERE player = ? AND stat = ?" + (lock ? " FOR UPDATE" : ""))) {
            s.setObject(1, player);
            s.setString(2, stat);
            try (ResultSet rows = s.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }
}
