package tennacle.postgres;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tennacle.storage.FileVault;
import tennacle.storage.MatchStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** Copies the file stores into the database, once. The files are only read, so they stay as a backup. */
public final class Imports {

    private static final Logger LOGGER = LoggerFactory.getLogger(Imports.class);
    private static final String NAME = "files-v1";
    /** A seat from a log that kept names alone, and nobody in the directory went by it. */
    static final UUID UNKNOWN = new UUID(0, 0);

    private record Seen(UUID id, String name, long at) {}

    private Imports() {}

    /** Rows already in the database win over the files. */
    public static void filesOnce(@NotNull Database db, @NotNull Path data) {
        db.transaction(c -> {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO imports (name) VALUES (?) ON CONFLICT DO NOTHING")) {
                s.setString(1, NAME);
                if (s.executeUpdate() == 0) return null;
            }
            try {
                int accounts = accounts(c, data.resolve("players"));
                Map<String, Seen> byName = new HashMap<>();
                int players = directory(c, data.resolve("directory.tsv"), byName);
                int matches = matches(c, data.resolve("matches"), byName);
                LOGGER.info("imported {} accounts, {} directory entries and {} matches from {}", accounts, players,
                        matches, data.toAbsolutePath());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return null;
        }).join();
    }

    private static int accounts(Connection c, Path dir) throws IOException, SQLException {
        if (!Files.isDirectory(dir)) return 0;
        int count = 0;
        try (Stream<Path> files = Files.list(dir); PreparedStatement s = c.prepareStatement(
                "INSERT INTO player_data (player, key, value) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".dat")).toList()) {
                String name = file.getFileName().toString();
                UUID player = UUID.fromString(name.substring(0, name.length() - ".dat".length()));
                for (Map.Entry<String, byte[]> entry : FileVault.entries(file).entrySet()) {
                    s.setObject(1, player);
                    s.setString(2, entry.getKey());
                    s.setBytes(3, entry.getValue());
                    s.addBatch();
                }
                count++;
            }
            s.executeBatch();
        }
        return count;
    }

    private static int directory(Connection c, Path file, Map<String, Seen> byName) throws IOException, SQLException {
        if (!Files.isReadable(file)) return 0;
        Map<UUID, Seen> newest = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] parts = line.split("\t");
            if (parts.length != 3) continue;
            Seen seen = new Seen(UUID.fromString(parts[0]), parts[1], Long.parseLong(parts[2]));
            newest.merge(seen.id(), seen, (a, b) -> a.at() >= b.at() ? a : b);
        }
        try (PreparedStatement s = c.prepareStatement("INSERT INTO players (id, name, last_seen) VALUES (?, ?, ?) "
                + "ON CONFLICT (id) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen "
                + "WHERE players.last_seen < excluded.last_seen")) {
            for (Seen seen : newest.values()) {
                s.setObject(1, seen.id());
                s.setString(2, seen.name());
                s.setLong(3, seen.at());
                s.addBatch();
                byName.merge(seen.name().toLowerCase(Locale.ROOT), seen, (a, b) -> a.at() >= b.at() ? a : b);
            }
            s.executeBatch();
        }
        return newest.size();
    }

    private static int matches(Connection c, Path dir, Map<String, Seen> byName) throws IOException, SQLException {
        if (!Files.isDirectory(dir)) return 0;
        int count = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".jsonl")).sorted().toList()) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    MatchStore.Match match;
                    try {
                        match = match(JsonParser.parseString(line).getAsJsonObject(), byName);
                    } catch (RuntimeException torn) {
                        LOGGER.warn("skipped an unreadable line in {}", file.getFileName());
                        continue;
                    }
                    if (PgMatches.insert(c, match)) count++;
                }
            }
        }
        return count;
    }

    // the oldest lines list each side as names, later ones as {id, name}, the newest as {team, name, color, seats}
    static MatchStore.Match match(JsonObject o, Map<String, Seen> byName) {
        List<MatchStore.Side> sides = new ArrayList<>();
        int index = 0;
        for (JsonElement side : o.getAsJsonArray("sides")) {
            index++;
            List<MatchStore.Seat> seats = new ArrayList<>();
            JsonObject described = side.isJsonObject() ? side.getAsJsonObject() : null;
            for (JsonElement seat : described != null ? described.getAsJsonArray("seats") : side.getAsJsonArray()) {
                if (seat.isJsonObject()) {
                    seats.add(new MatchStore.Seat(UUID.fromString(seat.getAsJsonObject().get("id").getAsString()),
                            seat.getAsJsonObject().get("name").getAsString()));
                } else {
                    Seen known = byName.get(seat.getAsString().toLowerCase(Locale.ROOT));
                    seats.add(new MatchStore.Seat(known == null ? UNKNOWN : known.id(), seat.getAsString()));
                }
            }
            sides.add(described != null
                    ? new MatchStore.Side(text(described, "team"), text(described, "name"), text(described, "color"), seats)
                    : new MatchStore.Side(String.valueOf(index), "", "white", seats));
        }
        String winner = text(o, "winner");
        return new MatchStore.Match(text(o, "id"), text(o, "code"), text(o, "mode"), text(o, "platform"), text(o, "map"),
                text(o, "outcome"), winner.isEmpty() ? null : winner, o.get("startedAt").getAsLong(),
                o.get("endedAt").getAsLong(), o.has("ticks") ? o.get("ticks").getAsLong() : 0, sides);
    }

    private static String text(JsonObject o, String field) {
        JsonElement value = o.get(field);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
