package tennacle.api.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.postgres.Database;
import tennacle.postgres.PgApiKeys;
import tennacle.postgres.PgDirectory;
import tennacle.postgres.PgMatches;
import tennacle.postgres.PgStats;
import tennacle.storage.MatchStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiServerTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final UUID TERM = UUID.randomUUID();
    private static final UUID DOG = UUID.randomUUID();

    private static EmbeddedPostgres pg;
    private static Database db;
    private static ApiServer api;
    private static String key;

    @BeforeAll
    static void start() throws Exception {
        pg = EmbeddedPostgres.start();
        db = Database.connect(pg.getJdbcUrl("postgres", "postgres"), "postgres", null, 4);
        api = ApiServer.start(db, "127.0.0.1", 0, 20);
        key = new PgApiKeys(db).issue(TERM, 1).join();
        PgDirectory directory = new PgDirectory(db);
        directory.seen(TERM, "ApiTerm", 1_000).join();
        directory.seen(DOG, "ApiDog", 2_000).join();
        PgStats stats = new PgStats(db);
        stats.update(TERM, "bedwars:career-kills", n -> 12).join();
        stats.update(DOG, "bedwars:career-kills", n -> 30).join();
        new PgMatches(db).add(new MatchStore.Match("m-1", "ABCDE", "bridge", "scrims", "Urban", "winner", "1", 0, 60_000, 1200,
                List.of(new MatchStore.Side("1", "Blue", "blue", List.of(new MatchStore.Seat(TERM, "ApiTerm"))),
                        new MatchStore.Side("2", "Red", "red", List.of(new MatchStore.Seat(DOG, "ApiDog")))))).join();
    }

    @AfterAll
    static void stop() throws Exception {
        api.stop();
        db.close();
        pg.close();
    }

    private static HttpResponse<String> get(String path, String withKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + path)).GET();
        if (withKey != null) request.header("API-Key", withKey);
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    @Test
    void playersStatsAndMatches() throws Exception {
        JsonObject player = json(get("/v1/players/apiterm", key));
        assertEquals(TERM.toString(), player.get("uuid").getAsString());
        assertEquals("ApiTerm", player.get("name").getAsString());

        JsonObject stats = json(get("/v1/players/" + TERM + "/stats", key));
        assertEquals(12, stats.getAsJsonObject("stats").getAsJsonObject("bedwars").get("kills").getAsInt());

        JsonObject matches = json(get("/v1/players/ApiTerm/matches?limit=5", key));
        JsonObject match = matches.getAsJsonArray("matches").get(0).getAsJsonObject();
        assertEquals("Urban", match.get("map").getAsString());
        assertEquals("Red", match.getAsJsonArray("sides").get(1).getAsJsonObject().get("name").getAsString());
        assertEquals(match, json(get("/v1/matches/m-1", key)), "the same match alone");

        JsonObject board = json(get("/v1/leaderboards/bedwars/kills", key));
        assertEquals("ApiDog", board.getAsJsonArray("entries").get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void keysAndErrors() throws Exception {
        assertEquals(401, get("/v1/key", null).statusCode());
        assertEquals(401, get("/v1/key", "tn_nope").statusCode());
        assertEquals(404, get("/v1/players/Nobody", key).statusCode());
        assertEquals(404, get("/v1/nothing", key).statusCode());
        assertEquals(400, get("/v1/players/ApiTerm/matches?limit=999", key).statusCode());
        assertTrue(json(get("/v1/players/Nobody", key)).get("error").getAsString().contains("Nobody"));
    }

    @Test
    void manyRequestsAreRefused() throws Exception {
        String own = new PgApiKeys(db).issue(DOG, 1).join();
        int refused = 0;
        for (int i = 0; i < 25; i++) if (get("/v1/key", own).statusCode() == 429) refused++;
        assertTrue(refused >= 4, "20 a minute: " + refused);
        assertTrue(get("/v1/key", own).headers().firstValue("Retry-After").isPresent());
    }
}
