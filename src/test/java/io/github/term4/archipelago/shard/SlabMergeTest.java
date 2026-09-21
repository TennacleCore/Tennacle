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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.8 {@code ItemSlab.onItemUse}, both branches. The merge checks the doubled cube with the excludes-NOBODY
 * {@code checkNoEntityCollision}, so the placer standing on the half they click refuses it - and the branch
 * {@code return}s either way, so nothing falls through to ItemBlock: no block, no sound, the use spent.
 * A slab of another kind never reaches that branch and goes through ItemBlock, whose check counts the placer
 * just the same: nothing in their legs either, and nothing sounds.
 */
class SlabMergeTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        tennacle.games.TestWiring.ensure(instance);
        MinecraftServer.getBlockManager().registerBlockPlacementRule(new SlabPlacementRule(Block.OAK_SLAB));
        MinecraftServer.getBlockManager().registerBlockPlacementRule(new SlabPlacementRule(Block.SPRUCE_SLAB));
    }

    @Test
    void theMergeUnderYouIsRefusedAndSilent() {
        EventNode<Event> routing = Shard.blockRouting();
        MinecraftServer.getGlobalEventHandler().addChild(routing);
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
            assertTrue(op.compat().legacySelfPlace(), "1.8's own placement boxes");
            shard.setBlock(new Vec(2400, 64, 2400), Block.OAK_SLAB.withProperty("type", "bottom"));

            click(p, Material.OAK_SLAB);
            assertEquals("bottom", shard.getBlock(new Vec(2400, 64, 2400)).getProperty("type"),
                    "the half they stand on never doubles under them");
            assertEquals(Block.AIR, shard.getBlock(new Vec(2400, 65, 2400)),
                    "and the branch returns: nothing falls through into their legs");
            assertEquals(List.of(), sounds(p), "a merge that never happened never sounds");

            click(p, Material.SPRUCE_SLAB); // another kind: ItemBlock's path, which counts the placer too
            assertEquals(Block.AIR, shard.getBlock(new Vec(2400, 65, 2400)), "nothing in their legs either");
            assertEquals(List.of(), sounds(p), "and nothing sounds");
        } finally {
            Polyp.getInstance().profiles().setPlayer(op, null);
            p.player.remove();
            shard.reset();
            MinecraftServer.getGlobalEventHandler().removeChild(routing);
        }
    }

    /** The lobby's path: no shard, so the stock listener owns it. Same refusal, same silence. */
    @Test
    void theSameHoldsOffAnyShard() {
        instance.loadChunk(2440 >> 4, 2440 >> 4).join();
        FakePlayer p = FakePlayer.connect(instance, new Pos(2440.5, 64.5, 2440.5), "SlabLobby");
        OptimizedPlayer op = (OptimizedPlayer) p.player;
        try {
            Polyp.getInstance().clientInfo().setProtocol(p.player, 47);
            Polyp.getInstance().profiles().setPlayer(op, MechanicsProfile.builder()
                    .set(MechanicsKeys.COMPAT, Preset.MMC18.compat())
                    .set(MechanicsKeys.FX, Fx.vanilla18())
                    .build());
            p.player.setGameMode(net.minestom.server.entity.GameMode.CREATIVE); // the lobby build mode
            instance.setBlock(2440, 64, 2440, Block.OAK_SLAB.withProperty("type", "bottom"));

            p.player.setItemInHand(PlayerHand.MAIN, ItemStack.of(Material.OAK_SLAB, 64));
            p.sent.clear();
            Shard.placementListener(new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN,
                    new Vec(2440, 64, 2440), BlockFace.TOP, 0.5f, 0.5f, 0.5f, false, false, 1), p.player);
            MinecraftServer.getSchedulerManager().processTickEnd();

            assertEquals("bottom", instance.getBlock(2440, 64, 2440).getProperty("type"), "no double under them");
            assertEquals(Block.AIR, instance.getBlock(2440, 65, 2440), "nothing in their legs");
            assertEquals(List.of(), sounds(p), "and nothing sounds; sent: " + p.sent.stream()
                    .map(x -> x.getClass().getSimpleName()).distinct().toList());
        } finally {
            Polyp.getInstance().profiles().setPlayer(op, null);
            instance.setBlock(2440, 64, 2440, Block.AIR);
            instance.setBlock(2440, 65, 2440, Block.AIR);
            p.player.remove();
        }
    }

    private static List<String> sounds(FakePlayer p) {
        return p.sent(SoundEffectPacket.class).stream().map(s -> s.soundEvent().key().asString()).toList();
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
