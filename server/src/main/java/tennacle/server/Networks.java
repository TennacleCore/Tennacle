package tennacle.server;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tennacle.boot.Config;
import tennacle.net.LocalBus;
import tennacle.net.LocalPresence;
import tennacle.net.Network;
import tennacle.redis.Redis;
import tennacle.redis.RedisBus;
import tennacle.redis.RedisPresence;

import java.util.concurrent.TimeUnit;

/** Redis when {@code tennacle.redis.url} is set: the bus and presence every server shares. This server alone otherwise. */
final class Networks {

    private static final Logger LOGGER = LoggerFactory.getLogger(Networks.class);

    private Networks() {}

    /** Before anything subscribes; the returned step closes it. */
    static @NotNull Runnable open() throws InterruptedException {
        String server = Config.string("tennacle.server-id", "server-1");
        String url = Config.value("tennacle.redis.url");
        if (url == null) {
            Network.use(new LocalBus(), new LocalPresence(), server);
            LOGGER.info("network: this server alone, as {}", server);
            return () -> Network.bus().close();
        }
        Redis redis = Redis.connect(url);
        RedisBus bus = new RedisBus(redis);
        if (!bus.ready(5, TimeUnit.SECONDS)) LOGGER.warn("the event bus is not subscribed yet");
        Network.use(bus, new RedisPresence(redis), server);
        LOGGER.info("network: redis, as {}", server);
        return () -> {
            bus.close();
            redis.close();
        };
    }
}
