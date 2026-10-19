package tennacle.server;

import io.github.term4.archipelago.Storage;
import io.github.term4.archipelago.Viewers;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.shard.ShardTabList;
import io.github.term4.archipelago.ticking.ExecutionDomain;
import io.github.term4.bloom.Bloom;
import io.github.term4.bloom.mapmaking.Session;
import io.github.term4.canvas.Canvas;
import io.github.term4.canvas.theme.Livery;
import io.github.term4.canvas.theme.Tint;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.polyp.platform.player.PlayerNames;
import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.timer.TaskSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tennacle.Msg;
import tennacle.PlayerPrefs;
import tennacle.access.Access;
import tennacle.Seen;
import tennacle.Sessions;
import tennacle.boot.Config;
import tennacle.boot.Mechanics;
import tennacle.boot.Shutdown;
import tennacle.commands.AppCommand;
import tennacle.commands.Commands;
import tennacle.dev.DevCommands;
import tennacle.dev.PresetCommands;
import tennacle.dev.SectionCommands;
import tennacle.dev.ShardCommands;
import tennacle.dev.SimTpsCommands;
import tennacle.games.GamesModule;
import tennacle.games.Matches;
import tennacle.games.map.MapPool;
import tennacle.games.watch.Tapes;
import tennacle.games.watch.Theaters;
import tennacle.lobby.LobbyModule;
import tennacle.lobby.Playground;
import tennacle.social.SocialModule;
import tennacle.storage.Atomic;
import tennacle.moderation.Moderation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The games server: the party, games and lobby modules over Grotto + Polyp + Archipelago, installed in that order.
 * Boot only - the pieces live in {@link Mechanics} (systems + bridges), {@link SocialModule}, {@link GamesModule},
 * {@link LobbyModule} and the staff tools ({@link DevCommands} and the rest), always registered behind their
 * permission nodes. {@code tennacle.dev-commands} hands every player those nodes: on a loopback bind by default.
 * {@code tennacle.test-pads} lays the lobby's test pads, off unless set.
 *
 * <p>Everything the deployment decides comes through {@link Config}: port, bind, directories, forwarding
 * secret, whether every player holds the staff tools. The libraries persist nothing - {@link Stores} is the server's
 * answer to where account data lives, installed here before anything reads it.
 */
public final class TestServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestServer.class);
    private static final int EDITOR_IDLE_MINUTES = 10;
    private static final java.util.Map<String, Long> editorEmptySince = new java.util.concurrent.ConcurrentHashMap<>();

    // an abandoned editor closes on its own, but the unsaved work survives as an .autosave sidecar
    private static void editorWatchdog() {
        for (var editing : Bloom.sessions().values()) {
            if (!editing.instance().getPlayers().isEmpty()) {
                editorEmptySince.remove(editing.id());
                continue;
            }
            long since = editorEmptySince.computeIfAbsent(editing.id(), id -> System.currentTimeMillis());
            if (System.currentTimeMillis() - since < EDITOR_IDLE_MINUTES * 60_000L) continue;
            editorEmptySince.remove(editing.id());
            if (autosave(editing)) {
                LOGGER.info("arena {} sat empty {}m - closed, unsaved edits in the .autosave",
                        editing.id(), EDITOR_IDLE_MINUTES);
            }
            editing.close();
        }
    }

    /** Every open session written beside its arena; {@code false} = nothing open, or none landed. */
    private static boolean autosaveAll() {
        boolean any = false;
        for (var editing : Bloom.sessions().values()) any |= autosave(editing);
        return any;
    }

    /** Writes {@code editing} beside its arena; {@code false} = it could not be written. */
    private static boolean autosave(Session editing) {
        if (editing == null) return false;
        Path sidecar = MapPool.arenas().resolve(editing.id() + ".bncl.autosave");
        try {
            Atomic.write(sidecar, editing.save());
            return true;
        } catch (Exception e) {
            LOGGER.error("arena autosave failed for {}", editing.id(), e);
            return false;
        }
    }

    /** {@code tennacle.palette=dark_purple,gold,...} - the tint a team with nothing authored falls back to,
     *  in ordinal order. Unset: red, blue, green, yellow, aqua, light purple, dark purple, gold. */
    private static void palette() {
        String configured = Config.value("tennacle.palette");
        if (configured == null) return;
        List<Tint> tints = Arrays.stream(configured.split(",")).map(String::trim).filter(t -> !t.isEmpty())
                .map(Tint::parse).toList();
        Livery.ordinals(tints);
        LOGGER.info("palette: {}", configured);
    }

    /** {@code tennacle.look=blocks=slime_block,cage=glass} - roles the network sets over every map's own.
     *  Where {@code tennacle.palette} changes the hue, this changes the block. */
    private static void look() {
        String configured = Config.value("tennacle.look");
        if (configured == null) return;
        Map<String, String> roles = new LinkedHashMap<>();
        for (String pair : configured.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) throw new IllegalStateException("tennacle.look takes <role>=<value>, got '" + pair.trim() + "'");
            roles.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        Livery.overrides(roles);
        LOGGER.info("look: {}", configured);
    }

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
                LOGGER.error("uncaught exception on {}", thread.getName(), error));
        try {
            boot();
        } catch (Throwable e) {
            LOGGER.error("boot failed", e);
            System.exit(1);
        }
    }

    /*
     * No chunk reaper here on purpose. Every world this server owns is bounded and rebuilt from its own bytes -
     * a built lobby, an arena's preloaded extent, an editor session - so nothing grows without bound, and
     * Instance.unloadChunk REMOVES the chunk's entities, which is a heavier act than reclaiming memory. A world
     * that generates or persists (an anvil-backed, procedurally grown one) needs a policy that saves on unload;
     * that policy belongs with that world, not here.
     */
    private static void boot() throws Exception {
        // the platform flags, before init
        System.setProperty("minestom.new-socket-write-lock", "true");
        System.setProperty("minestom.enforce-entity-interaction-range", "false"); // mechanics handles reach
        System.setProperty("minestom.shutdown-on-signal", "false"); // Shutdown owns the order
        // explicit rather than inherited: the lobby is small, the arenas are the load
        System.setProperty("minestom.chunk-view-distance", String.valueOf(Config.integer("tennacle.chunk-view-distance", 8)));
        System.setProperty("minestom.entity-view-distance", String.valueOf(Config.integer("tennacle.entity-view-distance", 5)));

        String bind = Config.string("tennacle.bind", "127.0.0.1"); // the proxy is the only client
        int port = Config.integer("tennacle.port", 25566);
        boolean loopback = bind.equals("127.0.0.1") || bind.equals("localhost") || bind.equals("::1");
        Path data = Config.path("tennacle.data-dir", "data");

        MinecraftServer server = MinecraftServer.init(auth(loopback));
        // the library routes listener, task and tick throwables here; the default prints to stderr, which the
        // log file never sees
        MinecraftServer.getExceptionManager().setExceptionHandler(e -> LOGGER.error("unhandled", e));
        Shutdown.install();

        Mechanics.install();
        // loopback is also the PRODUCTION shape behind Velocity, so the bind alone is the wrong gate:
        // the arsenal defaults on only where forwarding is unauthenticated too
        boolean unauthenticated = Config.value("tennacle.velocity-secret") == null
                && Config.value("tennacle.bungeeguard-tokens") == null;
        boolean devCommands = Config.flag("tennacle.dev-commands", loopback && unauthenticated);
        Access.install();
        tennacle.hypixel.HypixelApi.install(Config.value("tennacle.hypixel-api-key"));
        Access.owners(Arrays.stream(Config.string("tennacle.owners", "").split(",")).map(String::trim)
                .filter(owner -> !owner.isEmpty()).collect(Collectors.toSet()));
        Access.everyone(devCommands ? Set.of("tennacle.staff.*") : Set.of()); // a dev build hands everyone the staff tools
        InstanceContainer world = LobbyModule.world(Config.flag("tennacle.test-pads", false));
        Commands.install(); // /help reads the registry every command below lands in
        // members build into their shard's overlay, never the map (the bridge already decorated the
        // placement listener - order-independent)
        MinecraftServer.getGlobalEventHandler().addChild(Shard.blockRouting());
        ShardTabList.install(); // tab names + completion follow each shard's TabScope

        ShardManager shards = new ShardManager();
        ShardCommands.register(shards, world);
        DevCommands.register(Mechanics.polyp);
        SimTpsCommands.register(Mechanics.polyp, shards, world);
        PresetCommands.register(Mechanics.polyp, world); // /preset: the mechanics preset at each scope, live
        if (!devCommands) LOGGER.info("staff tools only for those who hold them (tennacle.dev-commands off)");
        palette(); // a seasonal look is one config line: every map's arms, kits, cages and names follow
        look();

        // the libraries store nothing: account data is the app's, and this is where it lands
        Stores stores = Stores.open(data);
        Seen.use(stores.directory());
        Moderation.store(stores.punishments());
        Path replays = Storage.dir("replay-dir", "replays");
        leftovers(data, replays);
        SocialModule.install();
        GamesModule.install(world, Playground.SPAWN, shards,
                new GamesModule.Data(stores.vault(), stores.matches(), replays, gameSecret(data)));
        LobbyModule.install(world);
        MinecraftServer.getGlobalEventHandler().addListener(PlayerDisconnectEvent.class,
                e -> stores.closer().left(e.getPlayer().getUuid()));
        PlayerPrefs.install();
        // /arena edit|save: the map maker - arena layers become ordinary shards the /shard family drives
        Bloom.install(MapPool.arenas(), shards, p -> p.setInstance(world, Playground.SPAWN));
        // a builder's: the edit verbs work on whatever world the builder stands in, the lobby included
        Commands.adopt("tennacle.build.", Bloom::registerDefaultCommands);
        Commands.describe(AppCommand.Audience.STAFF, "arena", "The map maker: edit, save, section a map.");
        Commands.describe(AppCommand.Audience.STAFF, "marker", "The markers of the map being edited.");
        Commands.describe(AppCommand.Audience.STAFF, "give", "An item by material, for the editor.");
        SectionCommands.install(); // /arena section reads starter geometry, so it is ours to add
        // a builder walking into the editor leaves whatever they were in first - the compass, the ghost
        // body and the rest belong to that session, not to this one. Editor to editor is just a hop
        Bloom.onEntering(p -> {
            if (Bloom.sessionOf(p) == null) Sessions.depart(p);
        });
        Sessions.register(p -> { // /l leaves the editor OPEN - the idle watchdog below owns closing
            var editing = Bloom.sessionOf(p);
            if (editing == null) return null;
            return () -> {
                Matches.toLobby(p);
                Msg.say(p, Msg.v(editing.id()), " stays open. ", Msg.run("/arena edit"), " steps back in; it closes after "
                        + EDITOR_IDLE_MINUTES + "m empty (autosave kept).");
            };
        });
        MinecraftServer.getSchedulerManager().buildTask(TestServer::editorWatchdog)
                .repeat(TaskSchedule.seconds(30)).schedule();

        // each library reads a viewer's protocol itself and decides what an unknown one means: modern by
        // default, since guessing legacy sends a modern client 1.8 wire tricks it draws literally
        Bloom.viewerProtocol(p -> Mechanics.polyp.clientInfo().getProtocol(p));
        Viewers.protocol(p -> Mechanics.polyp.clientInfo().getProtocol(p)); // archipelago's tab list and replays
        Canvas.viewerProtocol(p -> Mechanics.polyp.clientInfo().getProtocol(p));
        // a name completes where the viewer's tab lists it - the same scope on a 1.8 client as a modern one
        PlayerNames.visible(ShardTabList::listed);
        Canvas.protocolWhenUnknown(47); // chrome is drawn once, early: an unknown viewer gets the flat 1.8 look
        Canvas.theme(Msg.THEME);
        Canvas.screenTheme(Msg.MENUS);
        Canvas.profile("minemen", Msg.MINEMEN);
        Canvas.profile("scrims", Msg.SCRIMS);

        stores.installed().run();
        Health.install();
        // a map loads on its first game, off the tick thread (Matches.begin), and leaves once idle this long
        int idle = Config.integer("tennacle.map-idle-minutes", 10);
        Matches.grotto().expireBasesAfter(idle > 0 ? Duration.ofMinutes(idle) : null);
        shutdownSteps(stores);
        server.start(bind, port);
        LOGGER.info("listening on {}:{} (dev commands {})", bind, port, devCommands ? "on" : "off");
    }

    // what the last run left on disk: torn temp files go, an unsaved editor session is named
    private static void leftovers(Path data, Path replays) {
        Path arenas = MapPool.arenas();
        for (Path root : List.of(data, replays, arenas)) Atomic.sweep(root);
        if (!Files.isDirectory(arenas)) return;
        try (var files = Files.list(arenas)) {
            for (Path sidecar : files.filter(f -> f.getFileName().toString().endsWith(".bncl.autosave")).toList()) {
                LOGGER.warn("{} holds unsaved editor work from an earlier run", sidecar.getFileName());
            }
        } catch (IOException e) {
            LOGGER.warn("the arena directory was not checked for autosaves", e);
        }
    }

    private static void shutdownSteps(Stores stores) {
        Shutdown.step("live games", 15, () -> onTick(15, () -> {
            for (Game game : List.copyOf(Matches.grotto().games())) {
                if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            }
        }));
        Shutdown.step("replays", 20, () -> Tapes.flush(15, TimeUnit.SECONDS)); // the cut games' files land
        Shutdown.step("editor", 10, () -> onTick(10, TestServer::autosaveAll));
        Shutdown.step("player data", 15, () -> {
            // the kicks come after these steps, too late to stamp a last seen
            long now = System.currentTimeMillis();
            for (Player player : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
                stores.directory().seen(player.getUuid(), player.getUsername(), now);
            }
            stores.closer().close(10, TimeUnit.SECONDS);
        });
        // before the clocks: a stage shard ticks on its own domain, and nothing else closes one
        Shutdown.step("theaters", 10, () -> onTick(10, Theaters::closeAll));
        Shutdown.step("shard clocks", 10, ExecutionDomain::shutdown);
        Shutdown.step("health endpoint", 5, Health::stop);
    }

    /** Runs {@code action} on the tick thread and waits: game teardown is not safe from a shutdown thread. */
    private static void onTick(long seconds, Runnable action) {
        if (!MinecraftServer.isStarted() || MinecraftServer.isStopping()) return; // no tick loop left to wait on
        CountDownLatch done = new CountDownLatch(1);
        MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
            try {
                action.run();
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(seconds, TimeUnit.SECONDS)) LOGGER.warn("shutdown step did not finish on the tick thread");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Proxy forwarding. A secret or a token set is the production answer; the unauthenticated fallback is
     * allowed only on a loopback bind, where nothing but the local proxy can reach the socket - without that
     * gate any direct connection hands over whatever UUID it likes.
     */
    private static Auth auth(boolean loopback) {
        String velocity = Config.value("tennacle.velocity-secret");
        if (velocity != null) return new Auth.Velocity(velocity);
        String tokens = Config.value("tennacle.bungeeguard-tokens");
        if (tokens != null) {
            return new Auth.Bungee(Set.of(tokens.split(",")).stream().map(String::trim)
                    .filter(t -> !t.isEmpty()).collect(Collectors.toUnmodifiableSet()));
        }
        if (!loopback) {
            throw new IllegalStateException("binding " + Config.string("tennacle.bind", "127.0.0.1")
                    + " needs tennacle.velocity-secret or tennacle.bungeeguard-tokens: unauthenticated forwarding"
                    + " lets any direct connection claim any account");
        }
        LOGGER.warn("unauthenticated proxy forwarding on a loopback bind - set tennacle.velocity-secret before"
                + " exposing this port");
        return new Auth.Bungee(); // bungee auth lets 1.7/Via-proxy clients join
    }

    /** Generated and kept once rather than defaulting to a secret that lives in the source. */
    private static String gameSecret(Path data) throws Exception {
        String configured = Config.value("tennacle.game-secret");
        if (configured != null) return configured;
        Path file = data.resolve("game-secret");
        if (Files.isReadable(file)) {
            String stored = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (!stored.isEmpty()) return stored; // a blank file boots and then fails every launch
        }
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Atomic.write(file, generated.getBytes(StandardCharsets.UTF_8));
        LOGGER.warn("generated a game-id secret in {} - set tennacle.game-secret to control rotation",
                file.toAbsolutePath());
        return generated;
    }
}
