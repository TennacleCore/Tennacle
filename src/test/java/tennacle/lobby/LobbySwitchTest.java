package tennacle.lobby;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.canvas.hologram.Hologram;
import io.github.term4.polyp.Polyp;
import io.github.term4.polyp.platform.fixes.FixToggleConfig;
import io.github.term4.polyp.platform.fixes.FixesConfig;
import io.github.term4.polyp.platform.fixes.FixesSystem;
import io.github.term4.polyp.platform.player.Wardrobe;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.play.ChunkDataPacket;
import net.minestom.server.network.packet.server.play.DestroyEntitiesPacket;
import net.minestom.server.network.packet.server.play.RespawnPacket;
import net.minestom.server.network.packet.server.play.SpawnEntityPacket;
import net.minestom.server.network.packet.server.play.UnloadChunkPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.TestWiring;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A 1.8 client switching lobbies: each NPC of the new lobby filed in a chunk it holds, none of the old left. */
class LobbySwitchTest extends HeadlessServerTest {

    private static final Pos SPAWN = new Pos(0.5, 42, 0.5);

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        Wardrobe.install();
        FixesSystem.install(Polyp.getInstance(), FixesConfig.builder().legacyChunkReload(FixToggleConfig.on())
                .legacyWorldSwap(FixToggleConfig.on()).build());
    }

    @Test
    void legacySwitchKeepsNpcs() {
        InstanceContainer a = world();
        InstanceContainer b = world();
        List<Entity> inA = npcs(a);
        List<Entity> inB = npcs(b);
        FakePlayer p = FakePlayer.connect(a, SPAWN, "SwitchOld");
        try {
            Polyp.getInstance().clientInfo().setProtocol(p.player, 47);
            drain(p);
            switchTo(p, b);
            LegacyClient client = LegacyClient.of(p);
            for (Entity npc : inB) assertTrue(client.hittable(npc.getEntityId()), "B's " + npc.getEntityType() + " filed in a held chunk");
            for (Entity npc : inA) assertFalse(client.known(npc.getEntityId()), "A's " + npc.getEntityType() + " gone");
            switchTo(p, a);
            client = LegacyClient.of(p);
            for (Entity npc : inA) assertTrue(client.hittable(npc.getEntityId()), "back in A, its " + npc.getEntityType() + " filed");
            for (Entity npc : inB) assertFalse(client.known(npc.getEntityId()), "B's " + npc.getEntityType() + " gone");
        } finally {
            p.player.remove();
            inA.forEach(Entity::remove);
            inB.forEach(Entity::remove);
        }
    }

    private static InstanceContainer world() {
        InstanceContainer world = MinecraftServer.getInstanceManager().createInstanceContainer();
        world.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.OBSIDIAN));
        world.setChunkSupplier(LightingChunk::new);
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) world.loadChunk(x, z).join();
        Wardrobe.outfit(world, Playground::outfit);
        return world;
    }

    // a row across two chunks, like the lobby's
    private static List<Entity> npcs(InstanceContainer world) {
        List<Entity> out = new ArrayList<>();
        for (double x : new double[]{-5.5, 3.5}) {
            Pos at = new Pos(x, 42, 6.5, 180, 0);
            LivingEntity body = new LivingEntity(EntityType.VILLAGER);
            body.setNoGravity(true);
            body.setInstance(world, at).join();
            out.add(body);
            Hologram label = Hologram.at(at, (stand, where) -> stand.setInstance(world, where).join());
            label.line(2.5, Component.text("npc"));
            out.addAll(label.stands());
        }
        return out;
    }

    private static void switchTo(FakePlayer p, InstanceContainer world) {
        p.player.setVelocity(Vec.ZERO);
        p.player.setInstance(world, SPAWN).join();
        drain(p);
    }

    private static void drain(FakePlayer p) {
        for (int i = 0; i < 60; i++) {
            p.player.onChunkBatchReceived(64f);
            p.player.tick(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
        }
    }

    /**
     * What a 1.8 client files where: a held chunk arriving again is a new object, an entity lives in the object it
     * was filed in, one spawned into an unloaded chunk waits for it, an unload drops what its object holds, and a
     * respawn starts the world over.
     */
    private static final class LegacyClient {
        private final Map<Long, Integer> objects = new HashMap<>();
        private final Map<Integer, long[]> filed = new HashMap<>();
        private final Map<Integer, Long> waiting = new HashMap<>();
        private int made;

        static LegacyClient of(FakePlayer p) {
            LegacyClient client = new LegacyClient();
            for (SendablePacket sent : p.sent) {
                ServerPacket packet = SendablePacket.extractServerPacket(ConnectionState.PLAY, sent);
                if (packet != null) client.read(packet);
            }
            return client;
        }

        private void read(ServerPacket packet) {
            switch (packet) {
                case ChunkDataPacket c -> {
                    long key = key(c.chunkX(), c.chunkZ());
                    int object = ++made;
                    objects.put(key, object);
                    waiting.entrySet().removeIf(w -> {
                        if (w.getValue() != key) return false;
                        filed.put(w.getKey(), new long[]{key, object});
                        return true;
                    });
                }
                case UnloadChunkPacket u -> {
                    long key = key(u.chunkX(), u.chunkZ());
                    Integer object = objects.remove(key);
                    if (object != null) filed.values().removeIf(f -> f[0] == key && f[1] == object);
                }
                case SpawnEntityPacket s -> {
                    long key = key(s.position().chunkX(), s.position().chunkZ());
                    Integer object = objects.get(key);
                    if (object == null) waiting.put(s.entityId(), key);
                    else filed.put(s.entityId(), new long[]{key, object});
                }
                case RespawnPacket r -> {
                    objects.clear();
                    filed.clear();
                    waiting.clear();
                }
                case DestroyEntitiesPacket d -> d.entityIds().forEach(id -> {
                    filed.remove(id);
                    waiting.remove(id);
                });
                default -> {}
            }
        }

        boolean hittable(int id) {
            long[] f = filed.get(id);
            return f != null && objects.getOrDefault(f[0], -1) == f[1];
        }

        boolean known(int id) {
            return filed.containsKey(id) || waiting.containsKey(id);
        }

        private static long key(int x, int z) {
            return ((long) x << 32) | (z & 0xFFFFFFFFL);
        }
    }
}
