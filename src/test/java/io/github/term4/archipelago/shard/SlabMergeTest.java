package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.polyp.MechanicsKeys;
import io.github.term4.polyp.MechanicsProfile;
import io.github.term4.polyp.Polyp;
import io.github.term4.polyp.fx.Fx;
import io.github.term4.polyp.platform.player.OptimizedPlayer;
import io.github.term4.polyp.presets.Preset;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import net.minestom.server.network.packet.server.play.SoundEffectPacket;
import org.everbuild.blocksandstuff.blocks.placement.SlabPlacementRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Standing on a half slab, no slab lands in your own body - neither the same one doubling under you nor a
 * different one 1.8 would offset into your legs. Nothing lands, so nothing sounds.
 */
class SlabMergeTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        tennacle.games.TestWiring.ensure(instance);
        MinecraftServer.getBlockManager().registerBlockPlacementRule(new SlabPlacementRule(Block.OAK_SLAB));
        MinecraftServer.getBlockManager().registerBlockPlacementRule(new SlabPlacementRule(Block.SPRUCE_SLAB));
    }

    @Test
    void noSlabLandsInsideYou() {
        EventNode<Event> routing = Shard.blockRouting();
        MinecraftServer.getGlobalEventHandler().addChild(routing);
        List<Block> placed = new ArrayList<>();
        EventNode<Event> watch = EventNode.all("test:slab-watch");
        watch.addListener(PlayerBlockPlaceEvent.class, e -> placed.add(e.getBlock()));
        MinecraftServer.getGlobalEventHandler().addChild(watch);
        instance.loadChunk(2400 >> 4, 2400 >> 4).join();
        Shard shard = new Shard(instance);
        FakePlayer p = FakePlayer.connect(instance, new Pos(2400.5, 64.5, 2400.5), "SlabStand");
        OptimizedPlayer op = (OptimizedPlayer) p.player;
        try {
            Polyp.getInstance().clientInfo().setProtocol(p.player, 47); // the 1.8 client the report came from
            // the profile, not a direct apply: a shard hop re-pushes it and would overwrite one
            Polyp.getInstance().profiles().setPlayer(op, MechanicsProfile.builder()
                    .set(MechanicsKeys.COMPAT, Preset.MMC18.compat())
                    .set(MechanicsKeys.FX, Fx.vanilla18())
                    .build());
            shard.join(p.player);
            assertTrue(op.compat().legacyClient(), "a 1.8 placer");
            assertEquals(false, op.compat().legacySelfPlace(), "minemen refuses a self-overlapping placement");
            shard.setBlock(new Vec(2400, 64, 2400), Block.OAK_SLAB.withProperty("type", "bottom"));

            // the same slab: the merge that would fill the cell they stand in
            click(p, Material.OAK_SLAB);
            assertEquals("bottom", shard.getBlock(new Vec(2400, 64, 2400)).getProperty("type"),
                    "the half they stand on never doubles");
            assertEquals(Block.AIR, shard.getBlock(new Vec(2400, 65, 2400)), "and nothing lands in their legs");

            // a different slab: no merge to make, so 1.8 offsets it one cell up - into the same body
            click(p, Material.SPRUCE_SLAB);
            assertEquals(Block.AIR, shard.getBlock(new Vec(2400, 65, 2400)),
                    "nor does the offset 1.8 falls back to");

            assertEquals(List.of(), placed, "no placement happened");
            assertEquals(List.of(), p.sent(SoundEffectPacket.class).stream()
                    .map(s -> s.soundEvent().key().asString()).toList(), "so nothing sounds");
        } finally {
            Polyp.getInstance().profiles().setPlayer(op, null);
            p.player.remove();
            shard.reset();
            MinecraftServer.getGlobalEventHandler().removeChild(watch);
            MinecraftServer.getGlobalEventHandler().removeChild(routing);
        }
    }

    // standing on the half, clicking its top face
    private static void click(FakePlayer p, Material slab) {
        p.player.setItemInHand(PlayerHand.MAIN, ItemStack.of(slab, 64));
        p.sent.clear();
        MinecraftServer.getGlobalEventHandler().call(new PlayerPacketEvent(p.player,
                new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, new Vec(2400, 64, 2400), BlockFace.TOP,
                        0.5f, 0.5f, 0.5f, false, false, 1)));
        MinecraftServer.getSchedulerManager().processTickEnd();
    }
}
