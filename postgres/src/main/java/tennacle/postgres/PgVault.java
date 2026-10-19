package tennacle.postgres;

import io.github.term4.grotto.vault.Vault;
import io.github.term4.grotto.vault.VaultKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

/** Account data as one row per (player, key), holding the vault's opaque bytes. */
public final class PgVault implements Vault {

    private static final String UPSERT = "INSERT INTO player_data (player, key, value) VALUES (?, ?, ?) "
            + "ON CONFLICT (player, key) DO UPDATE SET value = excluded.value, updated_at = now()";

    private final Database db;

    public PgVault(@NotNull Database db) {
        this.db = db;
    }

    @Override
    public <T> @NotNull CompletableFuture<T> get(@NotNull UUID player, @NotNull VaultKey<T> key) {
        return db.query(c -> key.decode(read(c, player, key.id(), false)));
    }

    @Override
    public <T> @NotNull CompletableFuture<Void> set(@NotNull UUID player, @NotNull VaultKey<T> key, @NotNull T value) {
        byte[] bytes = key.encode(value);
        return db.query(c -> {
            write(c, player, key.id(), bytes);
            return null;
        });
    }

    /** Atomic across servers: the row is locked from the read to the write. */
    @Override
    public <T> @NotNull CompletableFuture<T> update(@NotNull UUID player, @NotNull VaultKey<T> key,
                                                    @NotNull UnaryOperator<T> fn) {
        return db.transaction(c -> {
            // a missing row has nothing to lock: make one first
            try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO player_data (player, key, value) VALUES (?, ?, NULL) ON CONFLICT DO NOTHING")) {
                s.setObject(1, player);
                s.setString(2, key.id());
                s.executeUpdate();
            }
            T updated = fn.apply(key.decode(read(c, player, key.id(), true)));
            write(c, player, key.id(), key.encode(updated));
            return updated;
        });
    }

    static @Nullable byte[] read(Connection c, UUID player, String key, boolean lock) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT value FROM player_data WHERE player = ? AND key = ?" + (lock ? " FOR UPDATE" : ""))) {
            s.setObject(1, player);
            s.setString(2, key);
            try (var rows = s.executeQuery()) {
                return rows.next() ? rows.getBytes(1) : null;
            }
        }
    }

    static void write(Connection c, UUID player, String key, byte[] bytes) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(UPSERT)) {
            s.setObject(1, player);
            s.setString(2, key);
            s.setBytes(3, bytes);
            s.executeUpdate();
        }
    }
}
