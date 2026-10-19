package tennacle.postgres;

import org.jetbrains.annotations.NotNull;
import tennacle.storage.MatchStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class PgMatches implements MatchStore {

    private final Database db;

    public PgMatches(@NotNull Database db) {
        this.db = db;
    }

    @Override
    public @NotNull CompletableFuture<Void> add(@NotNull Match match) {
        return db.transaction(c -> {
            insert(c, match);
            return null;
        });
    }

    @Override
    public boolean flush(long timeout, @NotNull TimeUnit unit) {
        return db.flush(timeout, unit);
    }

    /** False when a match with this id is already stored. */
    static boolean insert(Connection c, Match match) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO matches (id, code, mode, platform, outcome, winner, "
                + "started_at, ended_at, ticks) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
            s.setString(1, match.id());
            s.setString(2, match.code());
            s.setString(3, match.mode());
            s.setString(4, match.platform());
            s.setString(5, match.outcome());
            s.setString(6, match.winner());
            s.setLong(7, match.startedAt());
            s.setLong(8, match.endedAt());
            s.setLong(9, match.ticks());
            if (s.executeUpdate() == 0) return false;
        }
        try (PreparedStatement s = c.prepareStatement(
                "INSERT INTO match_players (match_id, side, seat, player, name) VALUES (?, ?, ?, ?, ?)")) {
            for (int side = 0; side < match.sides().size(); side++) {
                List<Seat> seats = match.sides().get(side);
                for (int seat = 0; seat < seats.size(); seat++) {
                    UUID id = seats.get(seat).id();
                    s.setString(1, match.id());
                    s.setInt(2, side);
                    s.setInt(3, seat);
                    s.setObject(4, Imports.UNKNOWN.equals(id) ? null : id);
                    s.setString(5, seats.get(seat).name());
                    s.addBatch();
                }
            }
            s.executeBatch();
        }
        return true;
    }
}
