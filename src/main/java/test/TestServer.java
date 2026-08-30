package test;

import test.commands.DevCommands;
import test.commands.ReplayCommands;
import test.commands.ShardCommands;
import test.commands.SimTpsCommands;
import test.games.boxing.Boxing;
import test.games.bridge.Bridge;
import test.games.Duels;
import test.games.sumo.Sumo;
import test.lobby.Lobby;
import test.lobby.Playground;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.shard.ShardTabList;
import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.InstanceContainer;

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
        Sumo.install();   // hypixel-preset ring-out
        Boxing.install(); // speed 2, first to 100 hits; /stats for career numbers
        Bridge.install(); // red vs blue, first to five goals
        Lobby.install();  // the lobby sidebar - game boards sit on top of it
        // /arena edit|save: the map maker - arena layers become ordinary shards the /shard family drives
        io.github.term4.bloom.Bloom.install(java.nio.file.Path.of("arenas"), shards,
                p -> p.setInstance(world, new Pos(0.5, 42, 0.5)));
        // legacy clients can't render the glowing region boxes - they get particle outlines
        io.github.term4.bloom.Bloom.legacyViewers(p -> Mechanics.polyp.clientInfo().isLegacy(p));
        // the server look; UNKNOWN protocol renders gradient-free (the Via handshake lags the join) -
        // never chunk a 1.8 client
        io.github.term4.canvas.Canvas.theme(Msg.THEME);
        io.github.term4.canvas.Canvas.theme("minemen", Msg.MINEMEN);
        io.github.term4.canvas.Canvas.theme("scrims", Msg.SCRIMS);
        io.github.term4.canvas.Canvas.legacyViewers(p -> {
            var info = Mechanics.polyp.clientInfo();
            return info.getProtocol(p) == io.github.term4.polyp.tracking.ClientVersion.UNKNOWN_PROTOCOL
                    || info.isLegacy(p);
        });

        String portOverride = System.getenv("TENNACLE_PORT");
        server.start("0.0.0.0", portOverride != null ? Integer.parseInt(portOverride) : 25566);
    }
}
