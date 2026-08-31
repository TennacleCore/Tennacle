package test;

import test.commands.DevCommands;
import test.commands.GrottoCommands;
import test.commands.LobbyCommands;
import test.commands.ReplayCommands;
import test.commands.ShardCommands;
import test.commands.SimTpsCommands;
import test.games.Duels;
import test.games.boxing.Boxing;
import test.games.bridge.Bridge;
import test.games.sumo.Sumo;
import test.lobby.Lobby;
import test.lobby.Playground;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.shard.ShardTabList;
import io.github.term4.bloom.Bloom;
import io.github.term4.canvas.Canvas;
import io.github.term4.polyp.tracking.ClientVersion;
import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.InstanceContainer;

import java.nio.file.Path;

/**
 * The full Archipelago + Polyp playground - the reference for using both libraries together.
 * {@code /shard create|join} a game, build/dig, fight with the 1.8 combat kit, {@code /replay} it back;
 * spectate from a second client. Boot only - the pieces live in {@link Mechanics} (systems + bridges),
 * {@link Playground} (world + kit), {@link ShardCommands}, {@link ReplayCommands}, {@link DevCommands}.
 */
public final class TestServer {

    public static void main(String[] args) {
        // the platform flags, before init
        System.setProperty("minestom.new-socket-write-lock", "true");
        System.setProperty("minestom.enforce-entity-interaction-range", "false"); // mechanics handles reach

        MinecraftServer server = MinecraftServer.init(new Auth.Bungee()); // bungee auth lets 1.7/Via-proxy clients join

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
        DevCommands.register(Mechanics.polyp);
        SimTpsCommands.register(Mechanics.polyp, shards, world);
        Duels.install(world, new Pos(0.5, 42, 0.5), shards); // /duel <player> [mode]: 1v1s over Grotto
        // the server's id convention; grotto stays blind. The secret IS the derivation capability - a real
        // deploy sets TENNACLE_GAME_SECRET (rotating it orphans screenshot-derivation for older games)
        GameIds.install(Duels.grotto(),
                System.getenv().getOrDefault("TENNACLE_GAME_SECRET", "tennacle-dev-secret"));
        GrottoCommands.register(shards); // /grotto: the live map (instances, games, players, shard trees)
        LobbyCommands.register(); // /spawn|/lobby|/l - a seated player departs their game first
        Sumo.install();   // hypixel-preset ring-out
        Boxing.install(); // speed 2, first to 100 hits; /stats for career numbers
        Bridge.install(); // red vs blue, first to five goals
        Lobby.install();  // the lobby sidebar - game boards sit on top of it
        // /arena edit|save: the map maker - arena layers become ordinary shards the /shard family drives
        Bloom.install(Path.of("arenas"), shards, p -> p.setInstance(world, new Pos(0.5, 42, 0.5)));
        // legacy clients can't render the glowing region boxes - they get particle outlines
        Bloom.legacyViewers(p -> Mechanics.polyp.clientInfo().isLegacy(p));
        Canvas.theme(Msg.THEME);
        Canvas.profile("minemen", Msg.MINEMEN);
        Canvas.profile("scrims", Msg.SCRIMS);
        // UNKNOWN protocol renders gradient-free (the Via handshake lags the join) - never chunk a 1.8 client
        Canvas.legacyViewers(p -> {
            var info = Mechanics.polyp.clientInfo();
            return info.getProtocol(p) == ClientVersion.UNKNOWN_PROTOCOL || info.isLegacy(p);
        });

        String portOverride = System.getenv("TENNACLE_PORT");
        server.start("0.0.0.0", portOverride != null ? Integer.parseInt(portOverride) : 25566);
    }
}
