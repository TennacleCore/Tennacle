package tennacle.postgres;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.api.ApiKeys;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PgApiKeys implements ApiKeys {

    private final Database db;

    public PgApiKeys(@NotNull Database db) {
        this.db = db;
    }

    @Override
    public @NotNull CompletableFuture<String> issue(@NotNull UUID owner, long now) {
        String key = ApiKeys.generate();
        return db.transaction(c -> {
            revoke(c, owner, now);
            try (PreparedStatement s = c.prepareStatement("INSERT INTO api_keys (key_hash, owner, created_at) VALUES (?, ?, ?)")) {
                s.setString(1, ApiKeys.hash(key));
                s.setObject(2, owner);
                s.setLong(3, now);
                s.executeUpdate();
            }
            return key;
        });
    }

    @Override
    public @NotNull CompletableFuture<Boolean> revoke(@NotNull UUID owner, long now) {
        return db.query(c -> revoke(c, owner, now));
    }

    @Override
    public @NotNull CompletableFuture<@Nullable UUID> owner(@NotNull String key) {
        return db.query(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT owner FROM api_keys WHERE key_hash = ? AND revoked_at = 0")) {
                s.setString(1, ApiKeys.hash(key));
                try (ResultSet rows = s.executeQuery()) {
                    return rows.next() ? rows.getObject(1, UUID.class) : null;
                }
            }
        });
    }

    private static boolean revoke(Connection c, UUID owner, long now) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE api_keys SET revoked_at = ? WHERE owner = ? AND revoked_at = 0")) {
            s.setLong(1, now);
            s.setObject(2, owner);
            return s.executeUpdate() > 0;
        }
    }
}
