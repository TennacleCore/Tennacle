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

/** A bed consumes a bare click the way vanilla's sleep attempt does; wool lands off it by sneaking alone. */
class BedClickTest extends HeadlessServerTest {

    private static final Vec FOOT = new Vec(2400, 64, 2400);
    private static final Vec HEAD = new Vec(2401, 64, 2400);
    private static final Vec OFF = new Vec(2400, 64, 2399); // the foot's north face

    @BeforeAll
    static void mechanics() {
        MechanicsBoot.ensure();
        BlockBehaviorRuleRegistrations.register(VanillaBlockBehaviour.INSTANCE.getBEDS());
    }

    @Test
    void standingClickPlacesNothing() {
        click(false, shard -> assertTrue(shard.getBlock(OFF).air(), "a bare click is a use, not a placement"));
    }

    @Test
    void sneakingClickPlaces() {
        click(true, shard -> assertTrue(shard.getBlock(OFF).compare(Block.WHITE_WOOL), "sneaking places off the bed"));
    }

    private void click(boolean sneaking, java.util.function.Consumer<Shard> check) {
        EventNode<Event> routing = Shard.blockRouting();
        MinecraftServer.getGlobalEventHandler().addChild(routing);
        instance.loadChunk(2400 >> 4, 2400 >> 4).join();
        Shard shard = new Shard(instance);
        FakePlayer builder = FakePlayer.connect(instance, new Pos(2400.5, 64, 2402.5), sneaking ? "BedSneaker" : "BedClicker");
        try {
            Polyp.getInstance().clientInfo().setProtocol(builder.player, 47); // the 1.8 client the report came from
            shard.join(builder.player);
            Block bed = Block.RED_BED.withProperties(Map.of("facing", "east", "part", "foot", "occupied", "false"));
            shard.setBlock(FOOT, bed);
            shard.setBlock(HEAD, bed.withProperty("part", "head"));
            builder.player.setItemInMainHand(ItemStack.of(Material.WHITE_WOOL));
            builder.player.setSneaking(sneaking);

            var click = new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, FOOT, BlockFace.NORTH, 0.5f, 0.5f, 0f, false, false, 1);
            MinecraftServer.getGlobalEventHandler().call(new PlayerPacketEvent(builder.player, click));
            check.accept(shard);
        } finally {
            builder.player.remove();
            shard.reset();
            MinecraftServer.getGlobalEventHandler().removeChild(routing);
        }
    }
}
