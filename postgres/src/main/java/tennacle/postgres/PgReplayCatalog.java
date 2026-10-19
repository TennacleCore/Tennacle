package tennacle.postgres;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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

/** A player's matches, with their replay where one is kept and the player may still watch it. */
public final class PgReplayCatalog implements ReplayCatalog {

    private record Row(String id, String code, String mode, String map, long startedAt, long endedAt, String winner,
                       boolean watchable, boolean bookmarked) {}

    private record SideName(String team, String name, String color) {}

    private interface Bind {
        void bind(PreparedStatement s, int at) throws SQLException;
    }

    private final Database db;
    private final long keepMillis;

    /** {@code keepMillis} as the replay store's: past it, only a bookmark keeps a replay watchable. */
    public PgReplayCatalog(@NotNull Database db, long keepMillis) {
        this.db = db;
        this.keepMillis = keepMillis;
    }

    @Override
    public @NotNull CompletableFuture<List<Record>> of(@NotNull UUID player, @NotNull String name, int limit) {
        return db.query(c -> records(c, matches(c, player, "m.id IN (SELECT match_id FROM match_players WHERE player = ?)",
                (s, at) -> s.setObject(at, player), limit)));
    }

    /** One match by its id, or null; watchable as it is for everyone. */
    public @NotNull CompletableFuture<@Nullable Record> match(@NotNull String id) {
        return db.query(c -> {
            List<Record> found = records(c, matches(c, null, "m.id = ?", (s, at) -> s.setString(at, id), 1));
            return found.isEmpty() ? null : found.getFirst();
        });
    }

    private List<Row> matches(Connection c, @Nullable UUID viewer, String where, Bind bind, int limit) throws SQLException {
        String bookmarked = "EXISTS (SELECT 1 FROM replay_holds h WHERE h.replay_id = r.id AND h.kind = 'bookmark' AND h.holder = ?)";
        try (PreparedStatement s = c.prepareStatement("SELECT m.id, coalesce(r.code, m.code), m.mode, "
                + "coalesce(nullif(m.map, ''), r.map, ''), m.started_at, m.ended_at, m.winner, "
                + "(r.id IS NOT NULL AND r.expired_at = 0 AND (? OR r.played_at >= ? OR " + bookmarked + ")), "
                + bookmarked + " FROM matches m LEFT JOIN replays r ON r.id = m.id "
                + "WHERE " + where + " ORDER BY m.ended_at DESC LIMIT ?")) {
            UUID who = viewer == null ? Imports.UNKNOWN : viewer;
            s.setBoolean(1, keepMillis <= 0);
            s.setLong(2, System.currentTimeMillis() - keepMillis);
            s.setObject(3, who);
            s.setObject(4, who);
            bind.bind(s, 5);
            s.setInt(6, limit);
            List<Row> out = new ArrayList<>();
            try (ResultSet rows = s.executeQuery()) {
                while (rows.next()) {
                    out.add(new Row(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                            rows.getLong(5), rows.getLong(6), rows.getString(7), rows.getBoolean(8), rows.getBoolean(9)));
                }
            }
            return out;
        }
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
            out.add(new Record(row.id(), row.code(), row.mode(), row.map(), row.endedAt(), row.endedAt() - row.startedAt(),
                    List.copyOf(sides.values()), row.winner(), false, row.watchable(), row.bookmarked()));
        }
        return out;
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
