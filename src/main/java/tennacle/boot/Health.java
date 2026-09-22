package tennacle.boot;

import com.sun.net.httpserver.HttpServer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.event.server.ServerTickMonitorEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tennacle.games.Matches;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * What the main tick thread is doing, which nothing watched: the shard clocks have their own watchdog, the
 * server tick had no monitor, no rate and no way to ask from outside. Logs a sustained overrun (rate limited,
 * so a stall is one line rather than a flood) and, when a port is configured, answers {@code /health}.
 */
public final class Health {

    private static final Logger LOGGER = LoggerFactory.getLogger(Health.class);
    private static final int WINDOW = 20; // a second of ticks
    private static final double TICK_MS = 50.0;
    private static final long REPORT_GAP_MS = 30_000;

    private static final double[] recent = new double[WINDOW];
    private static int at;
    private static long reportedAt;
    private static volatile double averageMs;
    private static volatile HttpServer http;
    private static final long BOOTED_AT = System.currentTimeMillis();

    private Health() {}

    public static void install() {
        MinecraftServer.getGlobalEventHandler().addListener(ServerTickMonitorEvent.class, e -> {
            double ms = e.getTickMonitor().getTickTime();
            synchronized (recent) {
                recent[at] = ms;
                at = (at + 1) % WINDOW;
                double total = 0;
                for (double sample : recent) total += sample;
                averageMs = total / WINDOW;
            }
            if (averageMs <= TICK_MS) return;
            long now = System.currentTimeMillis();
            if (now - reportedAt < REPORT_GAP_MS) return;
            reportedAt = now;
            LOGGER.warn("tick overrun: {}ms average over the last second ({} tps), {} players, {} games",
                    Math.round(averageMs), String.format("%.1f", tps()),
                    MinecraftServer.getConnectionManager().getOnlinePlayerCount(), games());
        });

        int port = Config.integer("tennacle.health-port", 0);
        if (port <= 0) return; // off unless asked for
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(Config.string("tennacle.health-bind", "127.0.0.1"), port), 0);
            server.createContext("/health", exchange -> {
                byte[] body = status().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.setExecutor(null);
            server.start();
            http = server;
            LOGGER.info("health endpoint on {}:{}/health", Config.string("tennacle.health-bind", "127.0.0.1"), port);
        } catch (IOException e) {
            LOGGER.error("could not open the health endpoint on port {}", port, e);
        }
    }

    public static void stop() {
        HttpServer server = http;
        if (server != null) server.stop(0);
    }

    public static double tps() {
        double ms = averageMs;
        return ms <= 0 ? 20.0 : Math.min(20.0, 1000.0 / Math.max(TICK_MS, ms));
    }

    private static int games() {
        try {
            return Matches.grotto().games().size();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static String status() {
        return "{\"status\":\"" + (averageMs > TICK_MS * 2 ? "degraded" : "ok")
                + "\",\"tps\":" + String.format(java.util.Locale.ROOT, "%.2f", tps())
                + ",\"tickMs\":" + String.format(java.util.Locale.ROOT, "%.2f", averageMs)
                + ",\"players\":" + MinecraftServer.getConnectionManager().getOnlinePlayerCount()
                + ",\"games\":" + games()
                + ",\"uptimeSeconds\":" + (System.currentTimeMillis() - BOOTED_AT) / 1000L + "}";
    }
}
