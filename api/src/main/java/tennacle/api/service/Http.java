package tennacle.api.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/** What both APIs answer with: JSON, and an {@code error} field with a status when it goes wrong. */
final class Http {

    static final class Failure extends RuntimeException {
        final int status;

        Failure(int status, String message) {
            super(message, null, false, false);
            this.status = status;
        }
    }

    interface Handler {
        JsonElement handle(HttpExchange exchange);
    }

    private Http() {}

    static void serve(HttpExchange exchange, Logger logger, Handler handler) throws IOException {
        try (exchange) {
            try {
                send(exchange, 200, handler.handle(exchange));
            } catch (RuntimeException e) {
                Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                if (cause instanceof Failure failure) {
                    send(exchange, failure.status, error(failure.getMessage()));
                } else {
                    logger.error("{} {} failed", exchange.getRequestMethod(), exchange.getRequestURI(), e);
                    send(exchange, 500, error("Something went wrong on our side."));
                }
            }
        }
    }

    static JsonElement notFound() {
        throw new Failure(404, "No such endpoint.");
    }

    static Map<String, String> query(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        return Arrays.stream(raw.split("&")).map(pair -> pair.split("=", 2)).filter(kv -> kv.length == 2)
                .collect(Collectors.toMap(kv -> java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        kv -> java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8), (a, b) -> b));
    }

    static JsonObject error(String message) {
        JsonObject out = new JsonObject();
        out.addProperty("error", message);
        return out;
    }

    static void send(HttpExchange exchange, int status, JsonElement body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
