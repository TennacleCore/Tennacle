package tennacle.api.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tennacle.boot.Config;
import tennacle.postgres.Database;
import tennacle.postgres.PgApiKeys;
import tennacle.postgres.PgDirectory;
import tennacle.postgres.PgReplayCatalog;
import tennacle.postgres.PgStats;
import tennacle.storage.Directory;
import tennacle.storage.ReplayCatalog;
import tennacle.storage.StatStore;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * The public API, v1: read-only JSON for players' bots and sites, one key per player ({@code /api new} in game,
 * sent as {@code API-Key}). Its own process, so a flood of requests never costs a game tick. Every response is
 * built field by field here: the stores' records can change without breaking anyone's bot.
 */
public final class ApiServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiServer.class);
    private static final UUID UNKNOWN = new UUID(0, 0);
    private static final long KEY_CACHE_MILLIS = 60_000; // a revoked key works this much longer at most

    private record Known(@Nullable UUID owner, long until) {}

    private static final class Failure extends RuntimeException {
        final int status;

        Failure(int status, String message) {
            super(message, null, false, false);
            this.status = status;
        }
    }

    private final HttpServer http;
    private final PgApiKeys keys;
    private final Directory directory;
    private final PgStats stats;
    private final PgReplayCatalog catalog;
    private final RateLimit limit;
    private final Map<String, Known> known = new ConcurrentHashMap<>();

    private ApiServer(HttpServer http, Database db, int perMinute) {
        this.http = http;
        this.keys = new PgApiKeys(db);
        this.directory = new PgDirectory(db);
        this.stats = new PgStats(db);
        this.catalog = new PgReplayCatalog(db);
        this.limit = new RateLimit(perMinute);
    }

    public static void main(String[] args) throws IOException {
        String url = Config.value("tennacle.postgres.url");
        if (url == null) throw new IllegalStateException("the API reads the database: set tennacle.postgres.url");
        Database db = Database.connect(url, Config.string("tennacle.postgres.user", "tennacle"),
                Config.value("tennacle.postgres.password"), Config.integer("tennacle.api.pool", 8));
        String bind = Config.string("tennacle.api.bind", "127.0.0.1");
        int port = Config.integer("tennacle.api.port", 8090);
        ApiServer server = start(db, bind, port, Config.integer("tennacle.api.per-minute", 120));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            db.close();
        }, "tennacle-api-stop"));
        LOGGER.info("public API on {}:{}/v1", bind, port);
    }

    public static @NotNull ApiServer start(@NotNull Database db, @NotNull String bind, int port, int perMinute) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(bind, port), 0);
        ApiServer server = new ApiServer(http, db, perMinute);
        http.createContext("/v1/", server::handle);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        return server;
    }

    public int port() {
        return http.getAddress().getPort();
    }

    public void stop() {
        http.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                if (!exchange.getRequestMethod().equals("GET")) throw new Failure(405, "Only GET is supported.");
                UUID owner = authorize(exchange);
                send(exchange, 200, route(exchange.getRequestURI(), owner));
            } catch (Failure failure) {
                send(exchange, failure.status, error(failure.getMessage()));
            } catch (RuntimeException e) {
                Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                if (cause instanceof Failure failure) {
                    send(exchange, failure.status, error(failure.getMessage()));
                } else {
                    LOGGER.error("{} failed", exchange.getRequestURI(), e);
                    send(exchange, 500, error("Something went wrong on our side."));
                }
            }
        }
    }

    private UUID authorize(HttpExchange exchange) {
        String key = exchange.getRequestHeaders().getFirst("API-Key");
        if (key == null || key.isBlank()) throw new Failure(401, "Send your key as the API-Key header. /api new in game makes one.");
        long now = System.currentTimeMillis();
        Known cached = known.get(key);
        if (cached == null || cached.until() < now) {
            cached = new Known(keys.owner(key).join(), now + KEY_CACHE_MILLIS);
            known.put(key, cached);
        }
        if (cached.owner() == null) throw new Failure(401, "That key isn't valid. It may have been revoked.");
        RateLimit.Spent spent = limit.spend(key, now);
        exchange.getResponseHeaders().add("RateLimit-Limit", String.valueOf(limit.perMinute()));
        exchange.getResponseHeaders().add("RateLimit-Remaining", String.valueOf(spent.remaining()));
        if (!spent.allowed()) {
            exchange.getResponseHeaders().add("Retry-After", String.valueOf(spent.retryAfterSeconds()));
            throw new Failure(429, "Too many requests. Try again in " + spent.retryAfterSeconds() + "s.");
        }
        return cached.owner();
    }

    private JsonElement route(URI uri, UUID owner) {
        List<String> path = List.of(uri.getPath().substring("/v1/".length()).split("/"));
        Map<String, String> query = query(uri.getRawQuery());
        return switch (path.getFirst()) {
            case "key" -> path.size() == 1 ? key(owner) : notFound();
            case "players" -> switch (path.size()) {
                case 2 -> player(player(path.get(1)));
                case 3 -> switch (path.get(2)) {
                    case "stats" -> stats(player(path.get(1)));
                    case "matches" -> matches(player(path.get(1)), bounded(query, "limit", 20, 50));
                    default -> notFound();
                };
                default -> notFound();
            };
            case "matches" -> path.size() == 2 ? match(path.get(1)) : notFound();
            case "leaderboards" -> path.size() == 3 ? leaderboard(path.get(1), path.get(2), bounded(query, "limit", 10, 100)) : notFound();
            default -> notFound();
        };
    }

    private JsonElement key(UUID owner) {
        JsonObject out = new JsonObject();
        out.addProperty("owner", owner.toString());
        out.addProperty("perMinute", limit.perMinute());
        return out;
    }

    private Directory.Entry player(String nameOrId) {
        Directory.Entry entry;
        try {
            entry = directory.byId(UUID.fromString(nameOrId)).join();
        } catch (IllegalArgumentException notAnId) {
            entry = directory.byName(nameOrId).join();
        }
        if (entry == null) throw new Failure(404, "No player " + nameOrId + " has joined.");
        return entry;
    }

    private static JsonObject player(Directory.Entry entry) {
        JsonObject out = new JsonObject();
        out.addProperty("uuid", entry.id().toString());
        out.addProperty("name", entry.name());
        out.addProperty("lastSeen", entry.lastSeen());
        return out;
    }

    // bedwars:career-kills reads as bedwars.kills
    private JsonElement stats(Directory.Entry entry) {
        JsonObject modes = new JsonObject();
        for (Map.Entry<String, Long> stat : stats.of(entry.id()).join().entrySet()) {
            int colon = stat.getKey().indexOf(':');
            String mode = colon < 0 ? "other" : stat.getKey().substring(0, colon);
            String name = stat.getKey().substring(colon + 1).replaceFirst("^career-", "");
            if (!modes.has(mode)) modes.add(mode, new JsonObject());
            modes.getAsJsonObject(mode).addProperty(name, stat.getValue());
        }
        JsonObject out = player(entry);
        out.add("stats", modes);
        return out;
    }

    private JsonElement matches(Directory.Entry entry, int limit) {
        JsonArray games = new JsonArray();
        for (ReplayCatalog.Record game : catalog.of(entry.id(), entry.name(), limit).join()) games.add(match(game));
        JsonObject out = player(entry);
        out.add("matches", games);
        return out;
    }

    private JsonElement match(String id) {
        ReplayCatalog.Record game = catalog.match(id).join();
        if (game == null) throw new Failure(404, "No match " + id + ".");
        return match(game);
    }

    private static JsonObject match(ReplayCatalog.Record game) {
        JsonObject out = new JsonObject();
        out.addProperty("id", game.id());
        out.addProperty("code", game.code());
        out.addProperty("mode", game.mode());
        out.addProperty("map", game.map());
        out.addProperty("endedAt", game.playedAt());
        out.addProperty("lengthMillis", game.lengthMillis());
        out.add("winner", game.winner() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(game.winner()));
        JsonArray sides = new JsonArray();
        for (ReplayCatalog.Side side : game.sides()) {
            JsonObject s = new JsonObject();
            s.addProperty("team", side.team());
            s.addProperty("name", side.name());
            s.addProperty("color", side.color());
            JsonArray players = new JsonArray();
            for (ReplayCatalog.Seat seat : side.seats()) {
                JsonObject p = new JsonObject();
                p.add("uuid", seat.id().equals(UNKNOWN) ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(seat.id().toString()));
                p.addProperty("name", seat.name());
                players.add(p);
            }
            s.add("players", players);
            sides.add(s);
        }
        out.add("sides", sides);
        JsonObject replay = new JsonObject();
        replay.addProperty("watchable", game.watchable()); // in game with /replay watch <code>
        out.add("replay", replay);
        return out;
    }

    private JsonElement leaderboard(String mode, String stat, int limit) {
        List<StatStore.Standing> top = stats.top(mode + ":career-" + stat, limit).join();
        Map<UUID, Directory.Entry> names = directory.byIds(top.stream().map(StatStore.Standing::player).toList()).join();
        JsonArray ranks = new JsonArray();
        for (int i = 0; i < top.size(); i++) {
            JsonObject row = new JsonObject();
            row.addProperty("rank", i + 1);
            row.addProperty("uuid", top.get(i).player().toString());
            Directory.Entry entry = names.get(top.get(i).player());
            row.add("name", entry == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(entry.name()));
            row.addProperty("value", top.get(i).value());
            ranks.add(row);
        }
        JsonObject out = new JsonObject();
        out.addProperty("mode", mode);
        out.addProperty("stat", stat);
        out.add("entries", ranks);
        return out;
    }

    private static JsonElement notFound() {
        throw new Failure(404, "No such endpoint.");
    }

    private static int bounded(Map<String, String> query, String name, int fallback, int max) {
        String raw = query.get(name);
        if (raw == null) return fallback;
        try {
            int value = Integer.parseInt(raw);
            if (value < 1 || value > max) throw new Failure(400, name + " goes from 1 to " + max + ".");
            return value;
        } catch (NumberFormatException e) {
            throw new Failure(400, name + " must be a number.");
        }
    }

    private static Map<String, String> query(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        return java.util.Arrays.stream(raw.split("&")).map(pair -> pair.split("=", 2)).filter(kv -> kv.length == 2)
                .collect(Collectors.toMap(kv -> java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        kv -> java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8), (a, b) -> b));
    }

    private static JsonObject error(String message) {
        JsonObject out = new JsonObject();
        out.addProperty("error", message);
        return out;
    }

    private static void send(HttpExchange exchange, int status, JsonElement body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
