package tennacle;

import net.minestom.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The stop the server never had. Minestom's own SIGINT hook kicks players and stops the loops, but JVM hooks
 * run in parallel, so it is turned off ({@code minestom.shutdown-on-signal}) and this one owns the order:
 * every registered step in turn, each with its own deadline, then the library stop.
 *
 * <p>Steps run on a plain thread, off the tick loop, so a step that blocks costs its deadline and nothing else.
 */
public final class Shutdown {

    private static final Logger LOGGER = LoggerFactory.getLogger(Shutdown.class);
    private static final List<Step> steps = new ArrayList<>();
    private static volatile boolean running;

    private record Step(String name, Runnable action, long seconds) {}

    private Shutdown() {}

    /** Registers a step; they run in registration order. */
    public static void step(@NotNull String name, long seconds, @NotNull Runnable action) {
        steps.add(new Step(name, action, seconds));
    }

    public static void install() {
        Runtime.getRuntime().addShutdownHook(new Thread(Shutdown::run, "tennacle-shutdown"));
    }

    /** Idempotent: a signal and an explicit stop cannot run the steps twice. */
    public static synchronized void run() {
        if (running) return;
        running = true;
        LOGGER.info("stopping: {} steps", steps.size());
        for (Step step : steps) {
            long started = System.currentTimeMillis();
            try {
                CompletableFuture.runAsync(step.action()).get(step.seconds(), TimeUnit.SECONDS);
                LOGGER.info("stopped {} ({}ms)", step.name(), System.currentTimeMillis() - started);
            } catch (Exception e) {
                LOGGER.error("shutdown step '{}' failed after {}ms", step.name(),
                        System.currentTimeMillis() - started, e);
            }
        }
        MinecraftServer.stopCleanly();
    }
}
