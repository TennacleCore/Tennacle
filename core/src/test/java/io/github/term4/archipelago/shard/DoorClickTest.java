package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.archipelago.testsupport.MechanicsBoot;
import io.github.term4.polyp.Polyp;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.boot.Mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A hand opens and shuts wooden doors and trapdoors, in a shard and out of one; iron ones wait for redstone. */
class DoorClickTest extends HeadlessServerTest {

    @BeforeAll
    static void mechanics() {
        MechanicsBoot.ensure();
        Mechanics.blockUses();
    }

    @Test
    void trapdoorOpensAndShuts() {
        Vec at = new Vec(2600, 64, 2600);
        try (Fixture f = new Fixture(at, "DoorTrap")) {
            f.shard.setBlock(at, Block.OAK_TRAPDOOR.withProperty("half", "bottom"));
            f.click(at);
            assertEquals("true", f.shard.getBlock(at).getProperty("open"));
            f.click(at);
            assertEquals("false", f.shard.getBlock(at).getProperty("open"), "and shut again");
        }
    }

    @Test
    void doorOpensBothHalves() {
        Vec lower = new Vec(2610, 64, 2600);
        try (Fixture f = new Fixture(lower, "DoorWood")) {
            Block door = Block.OAK_DOOR.withProperties(java.util.Map.of("facing", "north", "hinge", "left", "open", "false"));
            f.shard.setBlock(lower, door.withProperty("half", "lower"));
            f.shard.setBlock(lower.add(0, 1, 0), door.withProperty("half", "upper"));
            f.click(lower);
            assertEquals("true", f.shard.getBlock(lower).getProperty("open"));
            assertEquals("true", f.shard.getBlock(lower.add(0, 1, 0)).getProperty("open"), "the upper half with it");
        }
    }

    @Test
    void ironTrapdoorStaysShut() {
        Vec at = new Vec(2620, 64, 2600);
        try (Fixture f = new Fixture(at, "DoorIron")) {
            f.shard.setBlock(at, Block.IRON_TRAPDOOR.withProperty("half", "bottom"));
            f.click(at);
            assertEquals("false", f.shard.getBlock(at).getProperty("open"));
        }
    }

    @Test
    void baseLayerTrapdoorOpens() {
        Vec at = new Vec(2630, 64, 2600);
        instance.loadChunk(at.blockX() >> 4, at.blockZ() >> 4).join();
        FakePlayer p = FakePlayer.connect(instance, new Pos(2630.5, 64, 2602.5), "DoorBase");
        try {
            instance.setBlock(at, Block.OAK_TRAPDOOR.withProperty("half", "bottom"));
            Shard.placementListener(click(at), p.player);
            assertEquals("true", instance.getBlock(at).getProperty("open"), "the base listener answers too");
        } finally {
            instance.setBlock(at, Block.AIR);
            p.player.remove();
        }
    }

    private static ClientPlayerBlockPlacementPacket click(Vec at) {
        return new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, at, BlockFace.SOUTH, 0.5f, 0.5f, 1f, false, false, 1);
    }

    private static final class Fixture implements AutoCloseable {
        final EventNode<Event> routing = Shard.blockRouting();
        final Shard shard;
        final FakePlayer player;

        Fixture(Vec at, String name) {
            MinecraftServer.getGlobalEventHandler().addChild(routing);
            instance.loadChunk(at.blockX() >> 4, at.blockZ() >> 4).join();
            shard = new Shard(instance);
            player = FakePlayer.connect(instance, new Pos(at.x() + 0.5, at.y(), at.z() + 2.5), name);
            Polyp.getInstance().clientInfo().setProtocol(player.player, 47);
            shard.join(player.player);
        }

        void click(Vec at) {
            MinecraftServer.getGlobalEventHandler().call(new PlayerPacketEvent(player.player, DoorClickTest.click(at)));
        }

        @Override
        public void close() {
            player.player.remove();
            shard.reset();
            MinecraftServer.getGlobalEventHandler().removeChild(routing);
        }
    }
}
