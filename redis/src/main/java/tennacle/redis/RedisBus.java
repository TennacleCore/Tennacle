package tennacle.redis;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import tennacle.net.Bus;
import tennacle.net.Topic;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Pub/sub on every {@code tennacle:} channel, over one subscriber connection that reconnects when Redis drops it. */
public final class RedisBus implements Bus {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisBus.class);
    private static final String CHANNELS = "tennacle:*";
    private static final long RETRY_MILLIS = 2_000;

    private final Redis redis;
    private final Map<String, List<Consumer<String>>> listeners = new ConcurrentHashMap<>();
    private final ExecutorService deliver = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("tennacle-bus").factory());
    private final CountDownLatch subscribed = new CountDownLatch(1);
    private final Thread subscriber;
    private volatile boolean closed;
    private volatile JedisPubSub current;
    private volatile boolean down;

    public RedisBus(@NotNull Redis redis) {
        this.redis = redis;
        this.subscriber = Thread.ofPlatform().daemon().name("tennacle-bus-subscriber").start(this::listen);
    }

    /** Waits until the subscription is live: what is published before it never arrives. */
    public boolean ready(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
        return subscribed.await(timeout, unit);
    }

    @Override
    public <T> void publish(@NotNull Topic<T> topic, @NotNull T event) {
        String json = topic.encode(event);
        redis.run(pool -> pool.publish(topic.name(), json)).exceptionally(failed -> {
            LOGGER.warn("{} was not published", topic.name(), failed);
            return null;
        });
    }

    @Override
    public <T> void subscribe(@NotNull Topic<T> topic, @NotNull Consumer<T> listener) {
        listeners.computeIfAbsent(topic.name(), name -> new CopyOnWriteArrayList<>()).add(json -> listener.accept(topic.decode(json)));
    }

    @Override
    public void close() {
        closed = true;
        JedisPubSub pubSub = current;
        if (pubSub != null && pubSub.isSubscribed()) pubSub.punsubscribe();
        deliver.shutdown();
    }

    private void listen() {
        while (!closed) {
            JedisPubSub pubSub = new JedisPubSub() {
                @Override
                public void onPSubscribe(String pattern, int count) {
                    if (down) LOGGER.info("the event bus has Redis again");
                    down = false;
                    subscribed.countDown();
                }

                @Override
                public void onPMessage(String pattern, String channel, String message) {
                    for (Consumer<String> listener : listeners.getOrDefault(channel, List.of())) {
                        deliver.execute(() -> {
                            try {
                                listener.accept(message);
                            } catch (RuntimeException e) {
                                LOGGER.error("a {} listener failed", channel, e);
                            }
                        });
                    }
                }
            };
            current = pubSub;
            try (Jedis connection = new Jedis(redis.uri())) {
                connection.psubscribe(pubSub, CHANNELS);
            } catch (RuntimeException e) {
                if (closed) return;
                if (!down) LOGGER.warn("the event bus has no Redis; retrying every {}s", RETRY_MILLIS / 1000, e);
                down = true; // once per outage, not once a retry
                try {
                    Thread.sleep(RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }
}
