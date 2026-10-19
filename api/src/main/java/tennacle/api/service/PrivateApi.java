package tennacle.api.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tennacle.access.Access;
import tennacle.access.Grants;
import tennacle.moderation.Lengths;
import tennacle.moderation.Punishments;
import tennacle.moderation.Punishments.Kind;
import tennacle.moderation.Punishments.Punishment;
import tennacle.net.Network;
import tennacle.net.Presence;
import tennacle.net.Topics;
import tennacle.postgres.Database;
import tennacle.postgres.PgPunishments;
import tennacle.storage.Directory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Our own tools' API: a player's whole record, moderation that lands on whichever server they are on, and who is
 * where. Bound to the private network, each caller with its own token ({@code tennacle.api.private-tokens}, sent as
 * {@code Authorization: Bearer <token>}). It acts as the console does: above every staff rank.
 */
public final class PrivateApi {

    private static final Logger LOGGER = LoggerFactory.getLogger(PrivateApi.class);
    private static final int MAX_BODY = 16 * 1024;

    private final HttpServer http;
    private final Directory directory;
    private final Punishments punishments;
    private final Set<byte[]> tokens;

    private PrivateApi(HttpServer http, Database db, Directory directory, Set<String> tokens) {
        this.http = http;
        this.directory = directory;
        this.punishments = new PgPunishments(db);
        this.tokens = tokens.stream().map(t -> t.getBytes(StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.toSet());
    }

    static @NotNull PrivateApi start(@NotNull Database db, @NotNull Directory directory, @NotNull String bind, int port,
                                     @NotNull Set<String> tokens) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(bind, port), 0);
        PrivateApi api = new PrivateApi(http, db, directory, tokens);
        http.createContext("/internal/v1/", exchange -> Http.serve(exchange, LOGGER, api::handle));
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        return api;
    }

    public int port() {
        return http.getAddress().getPort();
    }

    void stop() {
        http.stop(0);
    }

    private JsonElement handle(HttpExchange exchange) {
        authorize(exchange);
        List<String> path = List.of(exchange.getRequestURI().getPath().substring("/internal/v1/".length()).split("/"));
        String method = exchange.getRequestMethod();
        if (path.getFirst().equals("servers") && path.size() == 1 && method.equals("GET")) return servers();
        if (!path.getFirst().equals("players") || path.size() < 2) return Http.notFound();
        Directory.Entry player = find(directory, path.get(1));
        if (path.size() == 2 && method.equals("GET")) return record(player);
        if (path.size() != 3) return Http.notFound();
        if (!method.equals("POST")) throw new Http.Failure(405, "Actions are POST.");
        JsonObject body = body(exchange);
        String by = text(body, "by", "API");
        return switch (path.get(2)) {
            case "ban" -> punish(player, Kind.BAN, body, by);
            case "mute" -> punish(player, Kind.MUTE, body, by);
            case "unban" -> lift(player, Kind.BAN, by);
            case "unmute" -> lift(player, Kind.MUTE, by);
            case "kick" -> kick(player, text(body, "reason", ""), by);
            default -> Http.notFound();
        };
    }

    // constant time, so a token can't be guessed a byte at a time
    private void authorize(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) throw new Http.Failure(401, "Send Authorization: Bearer <token>.");
        byte[] given = header.substring("Bearer ".length()).trim().getBytes(StandardCharsets.UTF_8);
        boolean known = false;
        for (byte[] token : tokens) known |= MessageDigest.isEqual(token, given);
        if (!known) throw new Http.Failure(401, "That token isn't one of ours.");
    }

    private JsonElement record(Directory.Entry player) {
        JsonObject out = ApiServer.player(player);
        Grants grants = Access.grants(player.id()).join();
        out.addProperty("role", grants.role().name().toLowerCase(Locale.ROOT));
        JsonArray nodes = new JsonArray();
        new TreeSet<>(grants.nodes()).forEach(nodes::add);
        out.add("grants", nodes);
        out.addProperty("owner", Access.owner(player.id(), player.name()));
        Presence.Here here = Network.presence().where(player.id()).join();
        out.add("online", here == null ? JsonNull.INSTANCE : online(here));
        long now = System.currentTimeMillis();
        JsonArray history = new JsonArray();
        for (Punishment p : punishments.history(player.id()).join()) history.add(punishment(p, now));
        out.add("punishments", history);
        return out;
    }

    private JsonElement servers() {
        JsonObject servers = new JsonObject();
        for (Map.Entry<String, Integer> server : Network.presence().servers().join().entrySet()) {
            servers.addProperty(server.getKey(), server.getValue());
        }
        JsonObject out = new JsonObject();
        out.add("servers", servers);
        return out;
    }

    private JsonElement punish(Directory.Entry player, Kind kind, JsonObject body, String by) {
        String typed = text(body, "duration", "");
        long length = Lengths.parse(typed);
        if (length < 0) throw new Http.Failure(400, "duration reads like 30m, 2h, 7d, 1w or perm.");
        long now = System.currentTimeMillis();
        long until = length == Punishments.FOREVER ? Punishments.FOREVER : now + length;
        Punishment added = punishments.add(player.id(), kind, text(body, "reason", ""), null, by, now, until).join();
        Network.bus().publish(Topics.PUNISHED, new Topics.Punished(Network.server(), player.id(), kind.name()));
        return punishment(added, now);
    }

    private JsonElement lift(Directory.Entry player, Kind kind, String by) {
        long now = System.currentTimeMillis();
        Punishment lifted = punishments.lift(player.id(), kind, null, by, now).join();
        if (lifted == null) throw new Http.Failure(409, player.name() + " isn't " + (kind == Kind.BAN ? "banned." : "muted."));
        Network.bus().publish(Topics.PUNISHED, new Topics.Punished(Network.server(), player.id(), kind.name()));
        return punishment(lifted, now);
    }

    private JsonElement kick(Directory.Entry player, String reason, String by) {
        if (Network.presence().where(player.id()).join() == null) throw new Http.Failure(409, player.name() + " isn't online.");
        long now = System.currentTimeMillis();
        Punishment kick = punishments.add(player.id(), Kind.KICK, reason, null, by, now, now).join();
        Network.bus().publish(Topics.KICKED, new Topics.Kicked(Network.server(), player.id(), reason));
        return punishment(kick, now);
    }

    static Directory.Entry find(Directory directory, String nameOrId) {
        Directory.Entry entry;
        try {
            entry = directory.byId(UUID.fromString(nameOrId)).join();
        } catch (IllegalArgumentException notAnId) {
            entry = directory.byName(nameOrId).join();
        }
        if (entry == null) throw new Http.Failure(404, "No player " + nameOrId + " has joined.");
        return entry;
    }

    private static JsonObject online(Presence.Here here) {
        JsonObject out = new JsonObject();
        out.addProperty("server", here.server());
        out.addProperty("since", here.since());
        out.addProperty("vanished", here.hidden());
        return out;
    }

    private static JsonObject punishment(Punishment p, long now) {
        JsonObject out = new JsonObject();
        out.addProperty("id", p.id());
        out.addProperty("kind", p.kind().name().toLowerCase(Locale.ROOT));
        out.addProperty("reason", p.reason());
        out.addProperty("by", p.byName());
        out.addProperty("at", p.at());
        out.add("until", p.kind() == Kind.KICK ? JsonNull.INSTANCE : p.permanent() ? new JsonPrimitive("forever") : new JsonPrimitive(p.until()));
        out.addProperty("active", p.active(now));
        if (p.liftedAt() != 0) {
            out.addProperty("liftedBy", p.liftedByName());
            out.addProperty("liftedAt", p.liftedAt());
        }
        return out;
    }

    private static JsonObject body(HttpExchange exchange) {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) throw new Http.Failure(413, "That body is too big.");
            if (bytes.length == 0) return new JsonObject();
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new Http.Failure(400, "The body is a JSON object.");
            return parsed.getAsJsonObject();
        } catch (IOException e) {
            throw new Http.Failure(400, "The body couldn't be read.");
        } catch (com.google.gson.JsonParseException e) {
            throw new Http.Failure(400, "The body isn't JSON.");
        }
    }

    private static String text(JsonObject body, String field, @Nullable String fallback) {
        JsonElement value = body.get(field);
        if (value == null || value.isJsonNull()) return fallback;
        return value.getAsString();
    }
}
