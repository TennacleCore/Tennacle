package tennacle.games.bedfight;

import io.github.term4.archipelago.shard.Shard;
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
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import org.everbuild.blocksandstuff.blocks.BlockBehaviorRuleRegistrations;
import org.everbuild.blocksandstuff.blocks.group.VanillaBlockBehaviour;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bed consumes a bare click the way vanilla's sleep attempt does; wool lands off it by sneaking alone. Both
 * ways in: a shard member's routed packet, and the base listener that answers a player in no shard at all.
 */
class BedClickTest extends HeadlessServerTest {

    @BeforeAll
    static void mechanics() {
        MechanicsBoot.ensure();
        BlockBehaviorRuleRegistrations.register(VanillaBlockBehaviour.INSTANCE.getBEDS());
    }

    @Test
    void standingClickPlacesNothing() {
        assertTrue(inAShard(false).air(), "a bare click is a use, not a placement");
    }

    @Test
    void sneakingClickPlaces() {
        assertTrue(inAShard(true).compare(Block.WHITE_WOOL), "sneaking places off the bed");
    }

    @Test
    void baseLayerClickPlacesNothing() {
        assertTrue(outsideAnyShard(false).air(), "the base listener consults the registered rule too");
    }

    @Test
    void baseLayerSneakPlaces() {
        assertTrue(outsideAnyShard(true).compare(Block.WHITE_WOOL), "and still places by sneaking");
    }

    private static Block bed() {
        return Block.RED_BED.withProperties(Map.of("facing", "east", "part", "foot", "occupied", "false"));
    }

    private static ClientPlayerBlockPlacementPacket northFaceOf(Vec foot) {
        return new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, foot, BlockFace.NORTH, 0.5f, 0.5f, 0f, false, false, 1);
    }

    private static FakePlayer arm(Pos at, String name, boolean sneaking) {
        FakePlayer builder = FakePlayer.connect(instance, at, name);
        Polyp.getInstance().clientInfo().setProtocol(builder.player, 47); // the 1.8 client the report came from
        builder.player.setItemInMainHand(ItemStack.of(Material.WHITE_WOOL));
        builder.player.setSneaking(sneaking);
        return builder;
    }

    /** What lands north of a shard's bed after a member's click. */
    private Block inAShard(boolean sneaking) {
        Vec foot = new Vec(2400, 64, 2400);
        Vec off = new Vec(2400, 64, 2399);
        EventNode<Event> routing = Shard.blockRouting();
        MinecraftServer.getGlobalEventHandler().addChild(routing);
        instance.loadChunk(2400 >> 4, 2400 >> 4).join();
        Shard shard = new Shard(instance);
        FakePlayer builder = arm(new Pos(2400.5, 64, 2402.5), sneaking ? "BedSneaker" : "BedClicker", sneaking);
        try {
            shard.join(builder.player);
            shard.setBlock(foot, bed());
            shard.setBlock(foot.add(1, 0, 0), bed().withProperty("part", "head"));
            MinecraftServer.getGlobalEventHandler().call(new PlayerPacketEvent(builder.player, northFaceOf(foot)));
            return shard.getBlock(off);
        } finally {
            builder.player.remove();
            shard.reset();
            MinecraftServer.getGlobalEventHandler().removeChild(routing);
        }
    }

    /** The same click from a player in no shard (the lobby, an editor's base layer): the base listener answers. */
    private Block outsideAnyShard(boolean sneaking) {
        Vec foot = new Vec(2410, 64, 2400);
        Vec off = new Vec(2410, 64, 2399);
        instance.loadChunk(2410 >> 4, 2400 >> 4).join();
        FakePlayer builder = arm(new Pos(2410.5, 64, 2402.5), sneaking ? "BaseBedSneaker" : "BaseBedClicker", sneaking);
        try {
            instance.setBlock(foot, bed());
            instance.setBlock(foot.add(1, 0, 0), bed().withProperty("part", "head"));
            Shard.placementListener(northFaceOf(foot), builder.player);
            return instance.getBlock(off);
        } finally {
            instance.setBlock(off, Block.AIR);
            instance.setBlock(foot, Block.AIR);
            instance.setBlock(foot.add(1, 0, 0), Block.AIR);
            builder.player.remove();
        }
    }
}
