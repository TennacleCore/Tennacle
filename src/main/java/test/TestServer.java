package test;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.shard.ShardTabList;
import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
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

        server.start("0.0.0.0", 25566);
    }
}
