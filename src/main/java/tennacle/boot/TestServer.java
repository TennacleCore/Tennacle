package tennacle.boot;

import tennacle.games.Careers;
import tennacle.GameIds;
import tennacle.Msg;
import tennacle.PlayerPrefs;
import tennacle.Rooms;
import tennacle.Sessions;
import tennacle.commands.SectionCommands;
import tennacle.games.watch.Theaters;
import tennacle.games.bedfight.BedFight;
import tennacle.games.matchmaking.Parties;
import tennacle.games.matchmaking.Duels;
import tennacle.games.matchmaking.Queues;
import tennacle.games.watch.Spectators;
import tennacle.games.watch.Tapes;
import tennacle.games.watch.Replays;
import tennacle.looks.GameSounds;
import tennacle.looks.TabStats;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Arrays;
import io.github.term4.canvas.theme.Tint;
import io.github.term4.canvas.theme.Livery;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.ticking.ExecutionDomain;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.Viewers;
import io.github.term4.archipelago.shard.ShardTabList;
import io.github.term4.bloom.Bloom;
import io.github.term4.bloom.mapmaking.Session;
import io.github.term4.canvas.Canvas;
import io.github.term4.canvas.chat.Chat;
import io.github.term4.canvas.tab.TabList;
import io.github.term4.polyp.platform.player.PlayerNames;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.timer.TaskSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tennacle.commands.DevCommands;
import tennacle.commands.Commands;
import tennacle.commands.AppCommand;
import tennacle.commands.GrottoCommands;
import tennacle.commands.LobbyCommands;
import tennacle.commands.ReplayCommands;
import tennacle.commands.RulesCommands;
import tennacle.commands.TeamCommands;
import tennacle.commands.ShardCommands;
import tennacle.commands.PresetCommands;
import tennacle.commands.SimTpsCommands;
import tennacle.commands.SpectateCommands;
import tennacle.lobby.Welcome;
import tennacle.games.TeamChat;
import tennacle.games.Matches;
import io.github.term4.archipelago.Storage;
import tennacle.games.boxing.Boxing;
import tennacle.games.bridge.Bridge;
import tennacle.games.sumo.Sumo;
import tennacle.lobby.Lobby;
import tennacle.lobby.Playground;
import tennacle.storage.Atomic;
import tennacle.storage.FileVault;
import tennacle.storage.MatchLog;
import tennacle.audit.Audit;
import tennacle.commands.AuditCommands;

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
import java.io.IOException;
import java.time.Duration;

/**
 * The games server: a lobby, three modes (sumo, boxing, bridge) on three networks' rules, duels, queues,
 * parties, spectating and live rule edits, over Grotto + Polyp + Archipelago. Boot only - the pieces live in
 * {@link Mechanics} (systems + bridges), {@link Playground} (the lobby world), {@link Matches} and the
 * {@code games} package, and the command classes. The dev toolset ({@link DevCommands}, the test pads, the
 * arsenal) exists only where {@code tennacle.dev-commands} says so - on a loopback bind by default.
 *
 * <p>Everything the deployment decides comes through {@link Config}: port, bind, directories, forwarding
 * secret, whether the dev commands exist. The libraries persist nothing - {@link FileVault} is the server's
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
        Path sidecar = arenaDir().resolve(editing.id() + ".bncl.autosave");
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
        InstanceContainer world = Playground.createWorld(devCommands); // the test pads ride the dev flag
        Playground.installListeners(world);
        Commands.help(); // /help reads the registry every command below lands in
        Welcome.install(); // the first lines an arrival reads
        // members build into their shard's overlay, never the map (the bridge already decorated the
        // placement listener - order-independent)
        MinecraftServer.getGlobalEventHandler().addChild(Shard.blockRouting());
        ShardTabList.install(); // tab names + completion follow each shard's TabScope

        ShardManager shards = new ShardManager();
        ReplayCommands.register(shards, world, devCommands);
        if (devCommands) {
            ShardCommands.register(shards, world);
            DevCommands.register(Mechanics.polyp);
            SimTpsCommands.register(Mechanics.polyp, shards, world);
            PresetCommands.register(Mechanics.polyp, world); // /preset: the mechanics preset at each scope, live
        } else {
            LOGGER.info("dev commands off (tennacle.dev-commands)");
        }
        palette(); // a seasonal look is one config line: every map's arms, kits, cages and names follow
        look();
        Matches.install(world, Playground.SPAWN, shards);

        // the libraries store nothing: account data is the app's, and this is where it lands
        FileVault vault = new FileVault(data.resolve("players"));
        Matches.grotto().vault(vault);
        MatchLog.install(data.resolve("matches"));
        Matches.grotto().onGameEnd(MatchLog::ended);
        Path replays = Storage.dir("replay-dir", "replays");
        leftovers(data, replays);
        Replays.install(replays, shards); // every game, spooled as it runs, kept as a file under its mode

        Duels.install(); // /duel <player> [mode] [platform]: 1v1s over Grotto
        Parties.install(); // /party: a side that duels and queues together
        Queues.install();  // /play's duels: the first parties that fill both teams start
        tennacle.games.matchmaking.Play.install(); // /play <game> [platform]: Hypixel's ids
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
        TeamCommands.register(); // /team: which ordinal is which color, and who is on it
        PlayerPrefs.install();
        TabStats.register(); // /tabstats: which seat counters the house tab lists
        GameSounds.register(); // /sounds: which cues a player hears
        // id convention; the secret is the derivation capability (rotate = old screenshots stop deriving)
        GameIds.install(Matches.grotto(), gameSecret(data));
        Audit.install(shards);
        AuditCommands.register(); // /audit: the server's worlds (instances, games, shard trees, players)
        GrottoCommands.register(); // /grotto: spectate, and a game id from a board code
        LobbyCommands.register(); // /spawn|/lobby|/l - a seated player departs their game first
        TeamChat.register(); // /shout: past team chat, the whole game
        Careers.register(); // /stats: every mode's career line
        Sumo.install();   // ring-out over a void plane; the platform picks the knockback
        Boxing.install(); // speed 2, first to 10 hits
        Bridge.install(); // blue vs red, first to five goals
        BedFight.install(); // break the bed, then eliminate
        tennacle.games.bedwars.BedWars.install(); // Hypixel's, on its maps: /play bedwars_eight_one
        Lobby.install();  // the lobby sidebar - game boards sit on top of it
        // /arena edit|save: the map maker - arena layers become ordinary shards the /shard family drives
        Bloom.install(arenaDir(), shards, p -> p.setInstance(world, Playground.SPAWN));
        Bloom.registerDefaultCommands();
        Commands.describe(AppCommand.Audience.DEV, "arena", "The map maker: edit, save, section a map.");
        Commands.describe(AppCommand.Audience.DEV, "marker", "The markers of the map being edited.");
        Commands.describe(AppCommand.Audience.DEV, "give", "An item by material, for the editor.");
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
        Chat.completesPlayers(PlayerNames.suggest());
        Canvas.protocolWhenUnknown(47); // chrome is drawn once, early: an unknown viewer gets the flat 1.8 look
        Canvas.theme(Msg.THEME);
        Canvas.profile("minemen", Msg.MINEMEN);
        Canvas.profile("scrims", Msg.SCRIMS);

        Health.install();
        // a map loads on its first game, off the tick thread (Matches.begin), and leaves once idle this long
        int idle = Config.integer("tennacle.map-idle-minutes", 10);
        Matches.grotto().expireBasesAfter(idle > 0 ? Duration.ofMinutes(idle) : null);
        shutdownSteps(vault);
        server.start(bind, port);
        LOGGER.info("listening on {}:{} (dev commands {})", bind, port, devCommands ? "on" : "off");
    }

    // what the last run left on disk: torn temp files go, an unsaved editor session is named
    private static void leftovers(Path data, Path replays) {
        for (Path root : List.of(data, replays, arenaDir())) Atomic.sweep(root);
        if (!Files.isDirectory(arenaDir())) return;
        try (var files = Files.list(arenaDir())) {
            for (Path sidecar : files.filter(f -> f.getFileName().toString().endsWith(".bncl.autosave")).toList()) {
                LOGGER.warn("{} holds unsaved editor work from an earlier run", sidecar.getFileName());
            }
        } catch (IOException e) {
            LOGGER.warn("the arena directory was not checked for autosaves", e);
        }
    }

    private static void shutdownSteps(FileVault vault) {
        Shutdown.step("live games", 15, () -> onTick(15, () -> {
            for (Game game : List.copyOf(Matches.grotto().games())) {
                if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            }
        }));
        Shutdown.step("replays", 20, () -> Tapes.flush(15, TimeUnit.SECONDS)); // the cut games' files land
        Shutdown.step("editor", 10, () -> onTick(10, TestServer::autosaveAll));
        Shutdown.step("player data", 15, () -> {
            vault.flush(10, TimeUnit.SECONDS);
            vault.close();
            MatchLog.flush(5, TimeUnit.SECONDS);
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
