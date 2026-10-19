package tennacle.postgres;

import org.jetbrains.annotations.NotNull;
import tennacle.storage.ReplayCatalog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** A player's matches, with their replay where one is kept. */
public final class PgReplayCatalog implements ReplayCatalog {

    private record Row(String id, String code, String mode, String map, long startedAt, long endedAt, String winner,
                       boolean watchable) {}

    private record SideName(String team, String name, String color) {}

    private final Database db;

    public PgReplayCatalog(@NotNull Database db) {
        this.db = db;
    }

    @Override
    public @NotNull CompletableFuture<List<Record>> of(@NotNull UUID player, @NotNull String name, int limit) {
        return db.query(c -> records(c, matches(c, "m.id IN (SELECT match_id FROM match_players WHERE player = ?)",
                s -> s.setObject(1, player), limit)));
    }

    /** One match by its id, or null. */
    public @NotNull CompletableFuture<Record> match(@NotNull String id) {
        return db.query(c -> {
            List<Record> found = records(c, matches(c, "m.id = ?", s -> s.setString(1, id), 1));
            return found.isEmpty() ? null : found.getFirst();
        });
    }

    private interface Bind {
        void bind(PreparedStatement s) throws SQLException;
    }

    private static List<Record> records(Connection c, List<Row> rows) throws SQLException {
        if (rows.isEmpty()) return List.of();
        String[] ids = rows.stream().map(Row::id).toArray(String[]::new);
        Map<String, Map<Integer, SideName>> names = sideNames(c, ids);
        Map<String, Map<Integer, List<Seat>>> seats = seats(c, ids);
        List<Record> out = new ArrayList<>();
        for (Row row : rows) {
            Map<Integer, SideName> named = names.getOrDefault(row.id(), Map.of());
            Map<Integer, List<Seat>> seated = seats.getOrDefault(row.id(), Map.of());
            TreeMap<Integer, Side> sides = new TreeMap<>();
            for (int side : seated.keySet()) {
                SideName n = named.getOrDefault(side, new SideName(String.valueOf(side + 1), "", "white"));
                sides.put(side, new Side(n.team(), n.name(), n.color(), seated.get(side)));
            }
            out.add(new Record(row.id(), row.code(), row.mode(), row.map(), row.endedAt(),
                    row.endedAt() - row.startedAt(), List.copyOf(sides.values()), row.winner(), false, row.watchable()));
        }
        return out;
    }

    private static List<Row> matches(Connection c, String where, Bind bind, int limit) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT m.id, coalesce(r.code, m.code), m.mode, "
                + "coalesce(nullif(m.map, ''), r.map, ''), m.started_at, m.ended_at, m.winner, "
                + "(r.id IS NOT NULL AND r.expired_at = 0) FROM matches m LEFT JOIN replays r ON r.id = m.id "
                + "WHERE " + where + " ORDER BY m.ended_at DESC LIMIT ?")) {
            bind.bind(s);
            s.setInt(2, limit);
            List<Row> out = new ArrayList<>();
            try (ResultSet rows = s.executeQuery()) {
                while (rows.next()) {
                    out.add(new Row(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                            rows.getLong(5), rows.getLong(6), rows.getString(7), rows.getBoolean(8)));
                }
            }
            return out;
        }
    }

    private static Map<String, Map<Integer, SideName>> sideNames(Connection c, String[] ids) throws SQLException {
        Map<String, Map<Integer, SideName>> out = new LinkedHashMap<>();
        try (PreparedStatement s = c.prepareStatement(
                "SELECT match_id, side, team, name, color FROM match_sides WHERE match_id = ANY (?)")) {
            s.setArray(1, c.createArrayOf("text", ids));
            try (ResultSet rows = s.executeQuery()) {
                while (rows.next()) {
                    out.computeIfAbsent(rows.getString(1), id -> new TreeMap<>())
                            .put(rows.getInt(2), new SideName(rows.getString(3), rows.getString(4), rows.getString(5)));
                }
            }
        }
        return out;
    }

    private static Map<String, Map<Integer, List<Seat>>> seats(Connection c, String[] ids) throws SQLException {
        Map<String, Map<Integer, List<Seat>>> out = new LinkedHashMap<>();
        try (PreparedStatement s = c.prepareStatement("SELECT match_id, side, player, name FROM match_players "
                + "WHERE match_id = ANY (?) ORDER BY match_id, side, seat")) {
            s.setArray(1, c.createArrayOf("text", ids));
            try (ResultSet rows = s.executeQuery()) {
                while (rows.next()) {
                    UUID id = rows.getObject(3, UUID.class);
                    out.computeIfAbsent(rows.getString(1), match -> new TreeMap<>())
                            .computeIfAbsent(rows.getInt(2), side -> new ArrayList<>())
                            .add(new Seat(id == null ? Imports.UNKNOWN : id, rows.getString(4)));
                }
            }
        }
        return out;
    }
}
