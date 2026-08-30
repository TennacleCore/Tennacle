package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.archipelago.testsupport.MechanicsBoot;
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
import org.everbuild.blocksandstuff.blocks.behavior.DoorOpenRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A member's click on a shard door runs the handler against the SHARD: both halves toggle there, the base map never changes. */
class ShardInteractionTest extends HeadlessServerTest {

    @BeforeAll
    static void mechanics() {
        MechanicsBoot.ensure();
        MinecraftServer.getBlockManager().registerHandler(Block.OAK_DOOR.key().asString(),
                () -> new DoorOpenRule(Block.OAK_DOOR));
    }

    @Test
    void doorOpensInsideTheShardOnly() {
        EventNode<Event> routing = Shard.blockRouting();
        MinecraftServer.getGlobalEventHandler().addChild(routing);
        instance.loadChunk(2300 >> 4, 2300 >> 4).join();
        Shard shard = new Shard(instance);
        FakePlayer opener = FakePlayer.connect(instance, new Pos(2300.5, 64, 2302.5), "DoorOpener");
        try {
            shard.join(opener.player);
            Block lower = Block.OAK_DOOR.withProperties(Map.of(
                    "half", "lower", "facing", "north", "hinge", "left", "open", "false", "powered", "false"));
            shard.setBlock(new Vec(2300, 64, 2300), lower);
            shard.setBlock(new Vec(2300, 65, 2300), lower.withProperty("half", "upper"));

            var click = new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, new Vec(2300, 64, 2300),
                    BlockFace.NORTH, 0.5f, 0.5f, 0.5f, false, false, 1);
            MinecraftServer.getGlobalEventHandler().call(new PlayerPacketEvent(opener.player, click));

            assertEquals("true", shard.getBlock(new Vec(2300, 64, 2300)).getProperty("open"), "lower half opened");
            assertEquals("true", shard.getBlock(new Vec(2300, 65, 2300)).getProperty("open"), "upper half synced");
            assertTrue(instance.getBlock(new Vec(2300, 64, 2300)).air(), "the base map never saw the door");
            assertTrue(instance.getBlock(new Vec(2300, 65, 2300)).air(), "or its other half");
        } finally {
            opener.player.remove();
            shard.reset();
            MinecraftServer.getGlobalEventHandler().removeChild(routing);
        }
    }
}
