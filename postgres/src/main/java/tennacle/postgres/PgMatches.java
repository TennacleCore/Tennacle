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
    public @NotNull CompletableFuture<Void> stats(@NotNull String match, @NotNull List<Line> lines, @NotNull List<Pair> pairs) {
        return db.transaction(c -> {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO match_stats (match_id, player, stat, value) "
                    + "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
                for (Line line : lines) {
                    s.setString(1, match);
                    s.setObject(2, line.player());
                    s.setString(3, line.stat());
                    s.setLong(4, line.value());
                    s.addBatch();
                }
                s.executeBatch();
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO match_pairs (match_id, player, other, stat, value) "
                    + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
                for (Pair pair : pairs) {
                    s.setString(1, match);
                    s.setObject(2, pair.player());
                    s.setString(3, pair.other());
                    s.setString(4, pair.stat());
                    s.setLong(5, pair.value());
                    s.addBatch();
                }
                s.executeBatch();
            }
            return null;
        });
    }

    @Override
    public boolean flush(long timeout, @NotNull TimeUnit unit) {
        return db.flush(timeout, unit);
    }

    /** False when a match with this id is already stored. */
    static boolean insert(Connection c, Match match) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO matches (id, code, mode, platform, map, outcome, "
                + "winner, started_at, ended_at, ticks) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
            s.setString(1, match.id());
            s.setString(2, match.code());
            s.setString(3, match.mode());
            s.setString(4, match.platform());
            s.setString(5, match.map());
            s.setString(6, match.outcome());
            s.setString(7, match.winner());
            s.setLong(8, match.startedAt());
            s.setLong(9, match.endedAt());
            s.setLong(10, match.ticks());
            if (s.executeUpdate() == 0) return false;
        }
        try (PreparedStatement s = c.prepareStatement("INSERT INTO match_sides (match_id, side, team, name, color) "
                + "VALUES (?, ?, ?, ?, ?)")) {
            for (int side = 0; side < match.sides().size(); side++) {
                Side described = match.sides().get(side);
                s.setString(1, match.id());
                s.setInt(2, side);
                s.setString(3, described.team());
                s.setString(4, described.name());
                s.setString(5, described.color());
                s.addBatch();
            }
            s.executeBatch();
        }
        try (PreparedStatement s = c.prepareStatement(
                "INSERT INTO match_players (match_id, side, seat, player, name) VALUES (?, ?, ?, ?, ?)")) {
            for (int side = 0; side < match.sides().size(); side++) {
                List<Seat> seats = match.sides().get(side).seats();
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
