package tennacle.hypixel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A player's Hypixel stats, read from the public API with the server's key. Nothing is stored: a fetch is cached for
 * a few minutes only, so a burst of imports costs one request per player.
 */
public final class HypixelApi {

    /** Why a fetch came back empty, in the words a player reads. */
    public static final class Failure extends RuntimeException {
        public Failure(@NotNull String reason) {
            super(reason, null, false, false);
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(HypixelApi.class);
    private static final long CACHE_MILLIS = 5 * 60_000;

    private record Cached(JsonObject stats, long at) {}

    private static final Map<UUID, Cached> CACHE = new ConcurrentHashMap<>();
    private static volatile @Nullable Function<UUID, CompletableFuture<JsonObject>> source;

    private HypixelApi() {}

    /** Reads with {@code key}; null or blank turns importing off. */
    public static void install(@Nullable String key) {
        source = key == null || key.isBlank() ? null : http(key.trim());
        CACHE.clear();
    }

    /** Where the player JSON comes from instead of the API. */
    public static void source(@Nullable Function<UUID, CompletableFuture<JsonObject>> fetch) {
        source = fetch;
        CACHE.clear();
    }

    public static boolean enabled() {
        return source != null;
    }

    /** The {@code player} object; fails with a {@link Failure} a player can be shown. */
    public static @NotNull CompletableFuture<JsonObject> player(@NotNull UUID player) {
        var fetch = source;
        if (fetch == null) return CompletableFuture.failedFuture(new Failure("Importing from Hypixel isn't set up on this server."));
        Cached cached = CACHE.get(player);
        if (cached != null && System.currentTimeMillis() - cached.at() < CACHE_MILLIS) {
            return CompletableFuture.completedFuture(cached.stats());
        }
        return fetch.apply(player).thenApply(stats -> {
            CACHE.put(player, new Cached(stats, System.currentTimeMillis()));
            return stats;
        });
    }

    private static Function<UUID, CompletableFuture<JsonObject>> http(String key) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return player -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.hypixel.net/v2/player?uuid=" + player))
                    .header("API-Key", key).timeout(Duration.ofSeconds(10)).GET().build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
                if (response.statusCode() == 429) throw new Failure("Hypixel is busy right now. Try again in a minute.");
                if (response.statusCode() == 403) {
                    LOGGER.warn("the Hypixel API refused the key: it may have expired");
                    throw new Failure("Importing from Hypixel is unavailable right now.");
                }
                if (response.statusCode() != 200) throw new Failure("Couldn't reach Hypixel. Try again in a minute.");
                JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                if (!body.has("player") || !body.get("player").isJsonObject()) {
                    throw new Failure("Hypixel has no record of you.");
                }
                return body.getAsJsonObject("player");
            });
        };
    }

    /** {@code root.path[0].path[1]...} as an object, or null where the path breaks. */
    public static @Nullable JsonObject object(@NotNull JsonObject root, @NotNull String... path) {
        JsonObject at = root;
        for (String step : path) {
            if (!at.has(step) || !at.get(step).isJsonObject()) return null;
            at = at.getAsJsonObject(step);
        }
        return at;
    }

    public static @Nullable String string(@Nullable JsonObject at, @NotNull String key) {
        if (at == null || !at.has(key) || !at.get(key).isJsonPrimitive()) return null;
        return at.get(key).getAsString();
    }
}
