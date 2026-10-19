package tennacle.redis;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import redis.clients.jedis.AbstractPipeline;
import tennacle.net.Presence;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code presence:<uuid>} a hash of where they are, {@code server:<name>} the set of who is there, {@code servers}
 * the names. Every key expires unless its server's beat renews it.
 */
public final class RedisPresence implements Presence {

    private static final String SERVERS = "servers";

    private final Redis redis;

    public RedisPresence(@NotNull Redis redis) {
        this.redis = redis;
    }

    @Override
    public @NotNull CompletableFuture<Void> joined(@NotNull Here here) {
        return redis.run(pool -> {
            try (AbstractPipeline pipe = pool.pipelined()) {
                put(pipe, here);
                pipe.sadd(SERVERS, here.server());
                pipe.sync();
            }
            return null;
        });
    }

    @Override
    public @NotNull CompletableFuture<Void> left(@NotNull UUID player, @NotNull String server) {
        return redis.run(pool -> {
            // a join elsewhere may already have moved them
            if (server.equals(pool.hget(key(player), "server"))) pool.del(key(player));
            pool.srem(serverKey(server), player.toString());
            return null;
        });
    }

    @Override
    public @NotNull CompletableFuture<Void> beat(@NotNull String server, @NotNull Iterable<Here> online) {
        return redis.run(pool -> {
            try (AbstractPipeline pipe = pool.pipelined()) {
                pipe.del(serverKey(server));
                for (Here here : online) put(pipe, here);
                pipe.sadd(SERVERS, server);
                pipe.sync();
            }
            return null;
        });
    }

    @Override
    public @NotNull CompletableFuture<@Nullable Here> where(@NotNull UUID player) {
        return redis.run(pool -> {
            Map<String, String> fields = pool.hgetAll(key(player));
            if (fields.isEmpty()) return null;
            return new Here(player, fields.getOrDefault("name", ""), fields.getOrDefault("server", ""),
                    Long.parseLong(fields.getOrDefault("since", "0")), "1".equals(fields.get("hidden")));
        });
    }

    @Override
    public @NotNull CompletableFuture<Map<String, Integer>> servers() {
        return redis.run(pool -> {
            Map<String, Integer> out = new TreeMap<>();
            for (String server : pool.smembers(SERVERS)) {
                if (!pool.exists(serverKey(server))) {
                    pool.srem(SERVERS, server); // its beat stopped
                    continue;
                }
                out.put(server, (int) pool.scard(serverKey(server)));
            }
            return out;
        });
    }

    private static void put(AbstractPipeline pipe, Here here) {
        String key = key(here.player());
        pipe.hset(key, Map.of("name", here.name(), "server", here.server(), "since", Long.toString(here.since()),
                "hidden", here.hidden() ? "1" : "0"));
        pipe.expire(key, STALE_SECONDS);
        pipe.sadd(serverKey(here.server()), here.player().toString());
        pipe.expire(serverKey(here.server()), STALE_SECONDS);
    }

    private static String key(UUID player) {
        return "presence:" + player;
    }

    private static String serverKey(String server) {
        return "server:" + server;
    }
}
