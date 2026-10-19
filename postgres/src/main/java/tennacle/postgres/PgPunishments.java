package tennacle.postgres;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.moderation.Punishments;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PgPunishments implements Punishments {

    private static final String COLUMNS = "id, player, kind, reason, by_id, by_name, at, until, lifted_by, lifted_by_name, lifted_at";

    private final Database db;

    public PgPunishments(@NotNull Database db) {
        this.db = db;
    }

    @Override
    public @NotNull CompletableFuture<Punishment> add(@NotNull UUID player, @NotNull Kind kind, @NotNull String reason,
                                                      @Nullable UUID by, @NotNull String byName, long at, long until) {
        return db.query(c -> {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO punishments (player, kind, reason, by_id, by_name, "
                    + "at, until) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS)) {
                s.setObject(1, player);
                s.setString(2, kind.name());
                s.setString(3, reason);
                s.setObject(4, by);
                s.setString(5, byName);
                s.setLong(6, at);
                s.setLong(7, until);
                try (ResultSet rows = s.executeQuery()) {
                    rows.next();
                    return punishment(rows);
                }
            }
        });
    }

    @Override
    public @NotNull CompletableFuture<@Nullable Punishment> active(@NotNull UUID player, @NotNull Kind kind, long now) {
        return db.query(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT " + COLUMNS + " FROM punishments WHERE player = ? "
                    + "AND kind = ? AND lifted_at = 0 AND until > ? ORDER BY at DESC, id DESC LIMIT 1")) {
                s.setObject(1, player);
                s.setString(2, kind.name());
                s.setLong(3, now);
                try (ResultSet rows = s.executeQuery()) {
                    return rows.next() ? punishment(rows) : null;
                }
            }
        });
    }

    @Override
    public @NotNull CompletableFuture<@Nullable Punishment> lift(@NotNull UUID player, @NotNull Kind kind,
                                                                 @Nullable UUID by, @NotNull String byName, long now) {
        return db.query(c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE punishments SET lifted_by = ?, lifted_by_name = ?, "
                    + "lifted_at = ? WHERE player = ? AND kind = ? AND lifted_at = 0 AND until > ? RETURNING " + COLUMNS)) {
                s.setObject(1, by);
                s.setString(2, byName);
                s.setLong(3, now);
                s.setObject(4, player);
                s.setString(5, kind.name());
                s.setLong(6, now);
                Punishment latest = null;
                try (ResultSet rows = s.executeQuery()) {
                    while (rows.next()) {
                        Punishment p = punishment(rows);
                        if (latest == null || p.at() > latest.at() || p.at() == latest.at() && p.id() > latest.id()) latest = p;
                    }
                }
                return latest;
            }
        });
    }

    @Override
    public @NotNull CompletableFuture<List<Punishment>> history(@NotNull UUID player) {
        return db.query(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT " + COLUMNS + " FROM punishments WHERE player = ? ORDER BY at DESC, id DESC")) {
                s.setObject(1, player);
                List<Punishment> out = new ArrayList<>();
                try (ResultSet rows = s.executeQuery()) {
                    while (rows.next()) out.add(punishment(rows));
                }
                return out;
            }
        });
    }

    private static Punishment punishment(ResultSet rows) throws SQLException {
        return new Punishment(rows.getLong(1), rows.getObject(2, UUID.class), Kind.valueOf(rows.getString(3)),
                rows.getString(4), rows.getObject(5, UUID.class), rows.getString(6), rows.getLong(7), rows.getLong(8),
                rows.getObject(9, UUID.class), rows.getString(10), rows.getLong(11));
    }
}
