package tennacle.net;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** One process alone. Events still go through JSON, so a listener sees what it would over Redis. */
public final class LocalBus implements Bus {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalBus.class);

    private final Map<String, List<Consumer<String>>> listeners = new ConcurrentHashMap<>();
    private final ExecutorService deliver = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("tennacle-bus").factory());

    @Override
    public <T> void publish(@NotNull Topic<T> topic, @NotNull T event) {
        String json = topic.encode(event);
        for (Consumer<String> listener : listeners.getOrDefault(topic.name(), List.of())) {
            deliver.execute(() -> {
                try {
                    listener.accept(json);
                } catch (RuntimeException e) {
                    LOGGER.error("a {} listener failed", topic.name(), e);
                }
            });
        }
    }

    @Override
    public <T> void subscribe(@NotNull Topic<T> topic, @NotNull Consumer<T> listener) {
        listeners.computeIfAbsent(topic.name(), name -> new CopyOnWriteArrayList<>()).add(json -> listener.accept(topic.decode(json)));
    }

    @Override
    public void close() {
        deliver.shutdown();
    }
}
