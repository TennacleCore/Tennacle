package tennacle.redis;

import com.github.fppt.jedismock.RedisServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.net.Presence;
import tennacle.net.RateLimiter;
import tennacle.net.Topics;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisNetworkTest {

    private static RedisServer server;
    private static Redis redis;

    @BeforeAll
    static void start() throws Exception {
        server = RedisServer.newRedisServer().start();
        redis = Redis.connect("redis://127.0.0.1:" + server.getBindPort());
    }

    @AfterAll
    static void stop() throws Exception {
        redis.close();
        server.stop();
    }

    @Test
    void eventsReachEveryProcess() throws Exception {
        RedisBus one = new RedisBus(redis);
        RedisBus two = new RedisBus(Redis.connect("redis://127.0.0.1:" + server.getBindPort()));
        try {
            List<Topics.Kicked> heard = new CopyOnWriteArrayList<>();
            one.subscribe(Topics.KICKED, heard::add);
            two.subscribe(Topics.KICKED, heard::add);
            assertTrue(one.ready(5, TimeUnit.SECONDS) && two.ready(5, TimeUnit.SECONDS));
            UUID player = UUID.randomUUID();
            one.publish(Topics.KICKED, new Topics.Kicked("server-1", player, "spam"));
            long until = System.currentTimeMillis() + 5_000;
            while (heard.size() < 2 && System.currentTimeMillis() < until) Thread.onSpinWait();
            assertEquals(List.of(new Topics.Kicked("server-1", player, "spam"), new Topics.Kicked("server-1", player, "spam")), heard,
                    "the publisher hears itself too");
        } finally {
            one.close();
            two.close();
        }
    }

    @Test
    void presenceFollowsServers() {
        RedisPresence presence = new RedisPresence(redis);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        presence.joined(new Presence.Here(a, "PresA", "lobby-1", 1_000, false)).join();
        presence.joined(new Presence.Here(b, "PresB", "lobby-2", 2_000, true)).join();
        Presence.Here here = presence.where(a).join();
        assertEquals("lobby-1", here.server());
        assertEquals("PresA", here.name());
        assertTrue(presence.where(b).join().hidden());
        assertEquals(Map.of("lobby-1", 1, "lobby-2", 1), presence.servers().join());

        presence.joined(new Presence.Here(a, "PresA", "lobby-2", 3_000, false)).join();
        presence.left(a, "lobby-1").join();
        assertEquals("lobby-2", presence.where(a).join().server(), "a leave from the old server keeps the new entry");
        presence.left(a, "lobby-2").join();
        assertNull(presence.where(a).join());

        presence.beat("lobby-2", List.of()).join();
        assertTrue(presence.servers().join().isEmpty(), "an empty beat empties its server");
    }

    @Test
    void limitsAreShared() {
        RateLimiter first = new RedisRateLimiter(redis);
        RateLimiter second = new RedisRateLimiter(redis);
        long now = 600_000;
        for (int i = 0; i < 3; i++) assertTrue(first.spend("k", 5, now).allowed());
        for (int i = 0; i < 2; i++) assertTrue(second.spend("k", 5, now).allowed(), "the same window from another process");
        RateLimiter.Spent over = second.spend("k", 5, now);
        assertTrue(!over.allowed() && over.retryAfterSeconds() > 0);
        assertTrue(first.spend("k", 5, now + 60_000).allowed(), "the next minute starts over");
    }
}
