package tennacle;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.ticking.ExecutionDomain;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.shard.ShardTabList;
import io.github.term4.bloom.Bloom;
import io.github.term4.canvas.Canvas;
import io.github.term4.canvas.tab.TabList;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.timer.TaskSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tennacle.commands.DevCommands;
import tennacle.commands.GrottoCommands;
import tennacle.commands.LobbyCommands;
import tennacle.commands.ReplayCommands;
import tennacle.commands.RulesCommands;
import tennacle.commands.ShardCommands;
import tennacle.commands.SimTpsCommands;
import tennacle.commands.SpectateCommands;
import tennacle.games.Duels;
import tennacle.games.Matches;
import tennacle.games.Parties;
import tennacle.games.Queues;
import tennacle.games.Spectators;
import tennacle.games.boxing.Boxing;
import tennacle.games.bridge.Bridge;
import tennacle.games.sumo.Sumo;
import tennacle.lobby.Lobby;
import tennacle.lobby.Playground;
import tennacle.storage.Atomic;
import tennacle.storage.FileVault;
import tennacle.storage.MatchLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The full Archipelago + Polyp playground - the reference for using both libraries together.
 * {@code /shard create|join} a game, build/dig, fight with the 1.8 combat kit, {@code /replay} it back;
 * spectate from a second client. Boot only - the pieces live in {@link Mechanics} (systems + bridges),
 * {@link Playground} (world + kit), {@link ShardCommands}, {@link ReplayCommands}, {@link DevCommands}.
 *
 * <p>Everything the deployment decides comes through {@link Config}: port, bind, directories, forwarding
 * secret, whether the dev commands exist. The libraries persist nothing - {@link FileVault} is the server's
 * answer to where account data lives, installed here before anything reads it.
 */
public final class TestServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestServer.class);
    private static final int EDITOR_IDLE_MINUTES = 10;
    private static final int UNLOAD_MARGIN = 2; // chunks kept beyond the view distance
    private static long editorEmptySince;

    // an abandoned editor closes on its own, but the unsaved work survives as an .autosave sidecar
    private static void editorWatchdog() {
        var editing = Bloom.session();
        if (editing == null || !editing.instance().getPlayers().isEmpty()) {
            editorEmptySince = 0;
            return;
        }
        if (editorEmptySince == 0) {
            editorEmptySince = System.currentTimeMillis();
            return;
        }
        if (System.currentTimeMillis() - editorEmptySince < EDITOR_IDLE_MINUTES * 60_000L) return;
        editorEmptySince = 0;
        if (autosave()) {
            LOGGER.info("arena {} sat empty {}m - closed, unsaved edits in the .autosave",
                    editing.id(), EDITOR_IDLE_MINUTES);
        }
        editing.close();
    }

    /** Writes the live editor session beside its arena; {@code false} = nothing open, or it could not be written. */
    private static boolean autosave() {
        var editing = Bloom.session();
        if (editing == null) return false;
        Path sidecar = arenaDir().resolve(editing.id() + ".bncl.autosave");
        try {
            Atomic.write(sidecar, editing.save());
            return true;
        } catch (Exception e) {
            LOGGER.error("arena autosave failed for {}", editing.id(), e);
            return false;
        }
    }

    public static Path arenaDir() {
        return Config.path("tennacle.arena-dir", "arenas");
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
        InstanceContainer world = Playground.createWorld();
        Playground.installListeners(world);
        // members build into their shard's overlay, never the map (the bridge already decorated the
        // placement listener - order-independent)
        MinecraftServer.getGlobalEventHandler().addChild(Shard.blockRouting());
        ShardTabList.install(); // tab names + completion follow each shard's TabScope

        ShardManager shards = new ShardManager();
        ShardCommands.register(shards, world);
        ReplayCommands.register(shards, world);
        boolean devCommands = Config.flag("tennacle.dev-commands", loopback);
        if (devCommands) {
            DevCommands.register(Mechanics.polyp);
            SimTpsCommands.register(Mechanics.polyp, shards, world);
        } else {
            LOGGER.info("dev commands off (tennacle.dev-commands)");
        }
        Matches.install(world, new Pos(0.5, 42, 0.5), shards);

        // the libraries store nothing: account data is the app's, and this is where it lands
        FileVault vault = new FileVault(data.resolve("players"));
        Matches.grotto().vault(vault);
        MatchLog.install(data.resolve("matches"));
        Matches.grotto().onGameEnd(MatchLog::ended);

        Duels.install(); // /duel <player> [mode] [platform]: 1v1s over Grotto
        Parties.install(); // /party: a side that duels and queues together
        Queues.install();  // /queue <mode> [platform]: the first two matching sides start
        SpectateCommands.register();
        Spectators.install();
        Rooms.install(); // who hears whom: game, spectators, party, shard tree
        // the chrome follows the viewer's game look (Rooms); the first send lands before Via reveals the protocol
        MinecraftServer.getGlobalEventHandler().addListener(PlayerSpawnEvent.class, e -> {
            if (e.isFirstSpawn()) Msg.onceProtocolKnown(e.getPlayer(), 60, () -> TabList.refresh(e.getPlayer()));
        });
        MinecraftServer.getGlobalEventHandler().addListener(PlayerDisconnectEvent.class,
                e -> vault.forget(e.getPlayer().getUuid()));
        RulesCommands.register(); // /rules: live modifiers over the running game
        PlayerPrefs.install();
        // id convention; the secret is the derivation capability (rotate = old screenshots stop deriving)
        GameIds.install(Matches.grotto(), gameSecret(data));
        GrottoCommands.register(shards); // /grotto: the live map (instances, games, players, shard trees)
        LobbyCommands.register(); // /spawn|/lobby|/l - a seated player departs their game first
        Sumo.install();   // hypixel-preset ring-out
        Boxing.install(); // speed 2, first to 100 hits; /stats for career numbers
        Bridge.install(); // red vs blue, first to five goals
        Lobby.install();  // the lobby sidebar - game boards sit on top of it
        // /arena edit|save: the map maker - arena layers become ordinary shards the /shard family drives
        Bloom.install(arenaDir(), shards, p -> p.setInstance(world, new Pos(0.5, 42, 0.5)));
        Bloom.registerDefaultCommands();
        Sessions.register(p -> { // /l leaves the editor OPEN - the idle watchdog below owns closing
            var editing = Bloom.session();
            if (editing == null || p.getInstance() != editing.instance()) return null;
            return () -> {
                Matches.toLobby(p);
                Msg.tag(p, "arena", editing.id() + " stays open - /arena edit steps back in; it closes after "
                        + EDITOR_IDLE_MINUTES + "m empty (autosave kept)");
            };
        });
        MinecraftServer.getSchedulerManager().buildTask(TestServer::editorWatchdog)
                .repeat(TaskSchedule.seconds(30)).schedule();
        MinecraftServer.getSchedulerManager().buildTask(() -> unloadIdleChunks(world))
                .repeat(TaskSchedule.minutes(2)).schedule();

        // each library reads a viewer's protocol itself and decides what an unknown one means: modern by
        // default, since guessing legacy sends a modern client 1.8 wire tricks it draws literally
        Bloom.viewerProtocol(p -> Mechanics.polyp.clientInfo().getProtocol(p));
        ShardTabList.viewerProtocol(p -> Mechanics.polyp.clientInfo().getProtocol(p));
        Canvas.viewerProtocol(p -> Mechanics.polyp.clientInfo().getProtocol(p));
        Canvas.protocolWhenUnknown(47); // chrome is drawn once, early: an unknown viewer gets the flat 1.8 look
        Canvas.theme(Msg.THEME);
        Canvas.profile("minemen", Msg.MINEMEN);
        Canvas.profile("scrims", Msg.SCRIMS);

        Health.install();
        warmArenas();
        Bloom.onSave(saved -> warmArenas()); // the edit replaced an arena: build the new world before a launch does
        shutdownSteps(vault);
        server.start(bind, port);
        LOGGER.info("listening on {}:{} (dev commands {})", bind, port, devCommands ? "on" : "off");
    }

    private static void shutdownSteps(FileVault vault) {
        Shutdown.step("live games", 15, () -> onTick(15, () -> {
            for (Game game : List.copyOf(Matches.grotto().games())) {
                if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            }
        }));
        Shutdown.step("editor", 10, () -> onTick(10, TestServer::autosave));
        Shutdown.step("player data", 15, () -> {
            vault.flush(10, TimeUnit.SECONDS);
            vault.close();
            MatchLog.flush(5, TimeUnit.SECONDS);
        });
        Shutdown.step("shard clocks", 10, ExecutionDomain::shutdown);
        Shutdown.step("health endpoint", 5, Health::stop);
    }

    /**
     * Reads, parses and BUILDS every mode's map off the tick thread, at boot and after a save. A cold map is
     * otherwise done inside the command that asks for it, and commands run on the tick loop. {@code instantiate}
     * caches per arena under its own lock, so a launch racing this waits rather than building a second world.
     */
    private static void warmArenas() {
        Thread.ofVirtual().name("tennacle-arena-warm").start(() -> {
            for (String name : Matches.modes()) {
                var mode = Matches.mode(name);
                if (mode == null) continue;
                try {
                    mode.arena().get().instantiate();
                } catch (RuntimeException e) {
                    LOGGER.warn("no map ready for {} yet: {}", name, e.getMessage());
                }
            }
        });
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

    // nothing unloaded the lobby: chunks accumulated for the life of the process as players roamed it
    private static void unloadIdleChunks(InstanceContainer world) {
        int keep = ServerFlag.CHUNK_VIEW_DISTANCE + UNLOAD_MARGIN;
        List<Chunk> watching = List.copyOf(world.getPlayers()).stream()
                .map(Player::getChunk).filter(java.util.Objects::nonNull).toList();
        for (Chunk chunk : List.copyOf(world.getChunks())) {
            boolean watched = false;
            for (Chunk seen : watching) {
                if (Math.abs(seen.getChunkX() - chunk.getChunkX()) <= keep
                        && Math.abs(seen.getChunkZ() - chunk.getChunkZ()) <= keep) {
                    watched = true;
                    break;
                }
            }
            if (!watched) world.unloadChunk(chunk.getChunkX(), chunk.getChunkZ());
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
        if (Files.isReadable(file)) return Files.readString(file, StandardCharsets.UTF_8).trim();
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Atomic.write(file, generated.getBytes(StandardCharsets.UTF_8));
        LOGGER.warn("generated a game-id secret in {} - set tennacle.game-secret to control rotation",
                file.toAbsolutePath());
        return generated;
    }
}
