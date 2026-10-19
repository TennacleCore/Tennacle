package tennacle.api.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tennacle.PlayerPrefs;
import tennacle.access.Access;
import tennacle.api.ApiKeys;
import tennacle.boot.Config;
import tennacle.net.LocalBus;
import tennacle.net.LocalPresence;
import tennacle.net.Network;
import tennacle.net.RateLimiter;
import tennacle.net.Topics;
import tennacle.postgres.Database;
import tennacle.postgres.PgApiKeys;
import tennacle.postgres.PgDirectory;
import tennacle.postgres.PgReplayCatalog;
import tennacle.postgres.PgStats;
import tennacle.postgres.PgVault;
import tennacle.redis.Redis;
import tennacle.redis.RedisBus;
import tennacle.redis.RedisPresence;
import tennacle.redis.RedisRateLimiter;
import tennacle.storage.Directory;
import tennacle.storage.ReplayCatalog;
import tennacle.storage.StatRouting;
import tennacle.storage.StatStore;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The public API, v1: read-only JSON for players' bots and sites, one key per player ({@code /api new} in game,
 * sent as {@code API-Key}). Its own process, so a flood of requests never costs a game tick. Every response is
 * built field by field here: the stores' records can change without breaking anyone's bot. The private API, for
 * our own tools, listens beside it on its own port ({@link PrivateApi}).
 */
public final class ApiServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiServer.class);
    private static final UUID UNKNOWN = new UUID(0, 0);
    private static final long KEY_CACHE_MILLIS = 60_000;

    /** {@code privatePort} 0 picks a free one; no {@code tokens}, no private API. */
    public record Settings(@NotNull String bind, int port, int perMinute, long keepMillis, @NotNull String privateBind,
                           int privatePort, @NotNull Set<String> tokens) {}

    private record Known(@Nullable UUID owner, long until) {}

    private final HttpServer http;
    private final @Nullable PrivateApi internal;
    private final PgApiKeys keys;
    private final Directory directory;
    private final PgStats stats;
    private final PgReplayCatalog catalog;
    private final RateLimiter limit;
    private final int perMinute;
    private final Map<String, Known> known = new ConcurrentHashMap<>();

    private ApiServer(HttpServer http, Database db, Settings settings, RateLimiter limit) throws IOException {
        this.http = http;
        this.keys = new PgApiKeys(db);
        this.directory = new PgDirectory(db);
        this.stats = new PgStats(db);
        this.catalog = new PgReplayCatalog(db, settings.keepMillis());
        this.limit = limit;
        this.perMinute = settings.perMinute();
        this.internal = settings.tokens().isEmpty() ? null
                : PrivateApi.start(db, directory, settings.privateBind(), settings.privatePort(), settings.tokens());
    }

    public static void main(String[] args) throws Exception {
        String url = Config.value("tennacle.postgres.url");
        if (url == null) throw new IllegalStateException("the API reads the database: set tennacle.postgres.url");
        Database db = Database.connect(url, Config.string("tennacle.postgres.user", "tennacle"),
                Config.value("tennacle.postgres.password"), Config.integer("tennacle.api.pool", 8));
        RateLimiter limit = network(Config.value("tennacle.redis.url"));
        Settings settings = new Settings(Config.string("tennacle.api.bind", "127.0.0.1"), Config.integer("tennacle.api.port", 8090),
                Config.integer("tennacle.api.per-minute", 120),
                TimeUnit.DAYS.toMillis(Config.integer("tennacle.replays.keep-days", 30)),
                Config.string("tennacle.api.private-bind", "127.0.0.1"), Config.integer("tennacle.api.private-port", 8091),
                Arrays.stream(Config.string("tennacle.api.private-tokens", "").split(",")).map(String::trim)
                        .filter(token -> !token.isEmpty()).collect(Collectors.toSet()));
        Access.owners(Arrays.stream(Config.string("tennacle.owners", "").split(",")).map(String::trim)
                .filter(owner -> !owner.isEmpty()).collect(Collectors.toSet()));
        ApiServer server = start(db, settings, limit);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            Network.bus().close();
            db.close();
        }, "tennacle-api-stop"));
        LOGGER.info("public API on {}:{}/v1", settings.bind(), server.port());
        if (server.internal == null) LOGGER.info("private API off: no tennacle.api.private-tokens");
        else LOGGER.info("private API on {}:{}/internal/v1", settings.privateBind(), server.internal.port());
    }

    // Redis carries the moderation events to the game servers and shares the rate limit; without it neither leaves
    private static RateLimiter network(@Nullable String url) throws InterruptedException {
        if (url == null) {
            Network.use(new LocalBus(), new LocalPresence(), "api");
            LOGGER.warn("no tennacle.redis.url: private API actions reach game servers only at the player's next login");
            return RateLimiter.local();
        }
        Redis redis = Redis.connect(url);
        RedisBus bus = new RedisBus(redis);
        if (!bus.ready(5, TimeUnit.SECONDS)) LOGGER.warn("the event bus is not subscribed yet");
        Network.use(bus, new RedisPresence(redis), "api");
        return new RedisRateLimiter(redis);
    }

    public static @NotNull ApiServer start(@NotNull Database db, @NotNull Settings settings, @NotNull RateLimiter limit) throws IOException {
        PlayerPrefs.vault(() -> new StatRouting(new PgVault(db), new PgStats(db))); // grants read through it
        HttpServer http = HttpServer.create(new InetSocketAddress(settings.bind(), settings.port()), 0);
        ApiServer server = new ApiServer(http, db, settings, limit);
        http.createContext("/v1/", exchange -> Http.serve(exchange, LOGGER, server::handle));
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        Network.bus().subscribe(Topics.KEY_REVOKED, e -> server.known.values().removeIf(k -> e.owner().equals(k.owner())));
        return server;
    }

    public int port() {
        return http.getAddress().getPort();
    }

    public @Nullable PrivateApi internal() {
        return internal;
    }

    public void stop() {
        http.stop(0);
        if (internal != null) internal.stop();
    }

    private JsonElement handle(HttpExchange exchange) {
        if (!exchange.getRequestMethod().equals("GET")) throw new Http.Failure(405, "Only GET is supported.");
        UUID owner = authorize(exchange);
        URI uri = exchange.getRequestURI();
        List<String> path = List.of(uri.getPath().substring("/v1/".length()).split("/"));
        Map<String, String> query = Http.query(uri.getRawQuery());
        return switch (path.getFirst()) {
            case "key" -> path.size() == 1 ? key(owner) : Http.notFound();
            case "players" -> switch (path.size()) {
                case 2 -> player(player(path.get(1)));
                case 3 -> switch (path.get(2)) {
                    case "stats" -> stats(player(path.get(1)));
                    case "matches" -> matches(player(path.get(1)), bounded(query, "limit", 20, 50));
                    default -> Http.notFound();
                };
                default -> Http.notFound();
            };
            case "matches" -> path.size() == 2 ? match(path.get(1)) : Http.notFound();
            case "leaderboards" -> path.size() == 3 ? leaderboard(path.get(1), path.get(2), bounded(query, "limit", 10, 100)) : Http.notFound();
            default -> Http.notFound();
        };
    }

    private UUID authorize(HttpExchange exchange) {
        String key = exchange.getRequestHeaders().getFirst("API-Key");
        if (key == null || key.isBlank()) throw new Http.Failure(401, "Send your key as the API-Key header. /api new in game makes one.");
        long now = System.currentTimeMillis();
        Known cached = known.get(key);
        if (cached == null || cached.until() < now) {
            cached = new Known(keys.owner(key).join(), now + KEY_CACHE_MILLIS);
            known.put(key, cached);
        }
        if (cached.owner() == null) throw new Http.Failure(401, "That key isn't valid. It may have been revoked.");
        RateLimiter.Spent spent = limit.spend(ApiKeys.hash(key), perMinute, now);
        exchange.getResponseHeaders().add("RateLimit-Limit", String.valueOf(perMinute));
        exchange.getResponseHeaders().add("RateLimit-Remaining", String.valueOf(spent.remaining()));
        if (!spent.allowed()) {
            exchange.getResponseHeaders().add("Retry-After", String.valueOf(spent.retryAfterSeconds()));
            throw new Http.Failure(429, "Too many requests. Try again in " + spent.retryAfterSeconds() + "s.");
        }
        return cached.owner();
    }

    private JsonElement key(UUID owner) {
        JsonObject out = new JsonObject();
        out.addProperty("owner", owner.toString());
        out.addProperty("perMinute", perMinute);
        return out;
    }

    private Directory.Entry player(String nameOrId) {
        return PrivateApi.find(directory, nameOrId);
    }

    static JsonObject player(Directory.Entry entry) {
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
        if (game == null) throw new Http.Failure(404, "No match " + id + ".");
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
        out.add("winner", game.winner() == null ? JsonNull.INSTANCE : new JsonPrimitive(game.winner()));
        JsonArray sides = new JsonArray();
        for (ReplayCatalog.Side side : game.sides()) {
            JsonObject s = new JsonObject();
            s.addProperty("team", side.team());
            s.addProperty("name", side.name());
            s.addProperty("color", side.color());
            JsonArray players = new JsonArray();
            for (ReplayCatalog.Seat seat : side.seats()) {
                JsonObject p = new JsonObject();
                p.add("uuid", seat.id().equals(UNKNOWN) ? JsonNull.INSTANCE : new JsonPrimitive(seat.id().toString()));
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
            row.add("name", entry == null ? JsonNull.INSTANCE : new JsonPrimitive(entry.name()));
            row.addProperty("value", top.get(i).value());
            ranks.add(row);
        }
        JsonObject out = new JsonObject();
        out.addProperty("mode", mode);
        out.addProperty("stat", stat);
        out.add("entries", ranks);
        return out;
    }

    private static int bounded(Map<String, String> query, String name, int fallback, int max) {
        String raw = query.get(name);
        if (raw == null) return fallback;
        try {
            int value = Integer.parseInt(raw);
            if (value < 1 || value > max) throw new Http.Failure(400, name + " goes from 1 to " + max + ".");
            return value;
        } catch (NumberFormatException e) {
            throw new Http.Failure(400, name + " must be a number.");
        }
    }
}
