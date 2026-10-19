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
import tennacle.moderation.Punishments;
import tennacle.net.Network;
import tennacle.net.Presence;
import tennacle.net.RateLimiter;
import tennacle.net.Topics;
import tennacle.postgres.PgPunishments;
import tennacle.storage.MatchStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiServerTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final UUID TERM = UUID.randomUUID();
    private static final UUID DOG = UUID.randomUUID();
    private static final String TOKEN = "test-token-0123456789";

    private static EmbeddedPostgres pg;
    private static Database db;
    private static ApiServer api;
    private static String key;

    @BeforeAll
    static void start() throws Exception {
        pg = EmbeddedPostgres.start();
        db = Database.connect(pg.getJdbcUrl("postgres", "postgres"), "postgres", null, 4);
        api = ApiServer.start(db, new ApiServer.Settings("127.0.0.1", 0, 20, 0, "127.0.0.1", 0, Set.of(TOKEN)),
                RateLimiter.local());
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

    private static HttpResponse<String> internal(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.internal().port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void privateRecordAndActions() throws Exception {
        assertEquals(401, internal("GET", "/internal/v1/players/ApiTerm", null, null).statusCode());
        assertEquals(401, internal("GET", "/internal/v1/players/ApiTerm", "wrong", null).statusCode());
        assertEquals(404, get("/internal/v1/players/ApiTerm", key).statusCode(), "not on the public port");

        List<Topics.Punished> heard = new CopyOnWriteArrayList<>();
        Network.bus().subscribe(Topics.PUNISHED, heard::add);
        HttpResponse<String> banned = internal("POST", "/internal/v1/players/ApiDog/ban", TOKEN,
                "{\"duration\":\"7d\",\"reason\":\"cheating\",\"by\":\"Panel\"}");
        assertEquals(200, banned.statusCode(), banned.body());
        assertTrue(json(banned).get("active").getAsBoolean());
        long until = System.currentTimeMillis() + 5_000;
        while (heard.isEmpty() && System.currentTimeMillis() < until) Thread.onSpinWait();
        assertEquals(new Topics.Punished(Network.server(), DOG, "BAN"), heard.getFirst(), "the game servers hear it");
        assertEquals(Punishments.Kind.BAN, new PgPunishments(db).active(DOG, Punishments.Kind.BAN, System.currentTimeMillis()).join().kind());

        Network.presence().joined(new Presence.Here(DOG, "ApiDog", "lobby-1", 5_000, false)).join();
        JsonObject record = json(internal("GET", "/internal/v1/players/ApiDog", TOKEN, null));
        assertEquals("player", record.get("role").getAsString());
        assertEquals("lobby-1", record.getAsJsonObject("online").get("server").getAsString());
        assertEquals("cheating", record.getAsJsonArray("punishments").get(0).getAsJsonObject().get("reason").getAsString());
        assertEquals(1, json(internal("GET", "/internal/v1/servers", TOKEN, null)).getAsJsonObject("servers").get("lobby-1").getAsInt());

        assertEquals(200, internal("POST", "/internal/v1/players/ApiDog/unban", TOKEN, "{\"by\":\"Panel\"}").statusCode());
        assertEquals(409, internal("POST", "/internal/v1/players/ApiDog/unban", TOKEN, null).statusCode(), "nothing left to lift");
        assertEquals(400, internal("POST", "/internal/v1/players/ApiDog/mute", TOKEN, "{\"duration\":\"5y\"}").statusCode());
        assertEquals(409, internal("POST", "/internal/v1/players/ApiTerm/kick", TOKEN, "{}").statusCode(), "not online anywhere");
        assertEquals(200, internal("POST", "/internal/v1/players/ApiDog/kick", TOKEN, "{\"reason\":\"afk\"}").statusCode());
        assertEquals(405, internal("GET", "/internal/v1/players/ApiDog/kick", TOKEN, null).statusCode());
    }
}
