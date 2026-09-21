package io.github.term4.archipelago.shard;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.archipelago.testsupport.MechanicsBoot;
import io.github.term4.polyp.MechanicsKeys;
import io.github.term4.polyp.MechanicsProfile;
import io.github.term4.polyp.Polyp;
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
import org.everbuild.blocksandstuff.blocks.BlockBehaviorRuleRegistrations;
import org.everbuild.blocksandstuff.blocks.group.VanillaBlockBehaviour;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A chest consumes a bare click the way vanilla's open does, so the click a 1.8 client sends without predicting a
 * placement - the chest under its own feet, or the side of the chest it stands beside - lands nothing in its body.
 * Sneaking with a block places off it. Both ways in: the routed packet and the base listener.
 */
class ChestClickTest extends HeadlessServerTest {

    @BeforeAll
    static void mechanics() {
        MechanicsBoot.ensure();
        BlockBehaviorRuleRegistrations.register(VanillaBlockBehaviour.INSTANCE.getCHESTS());
    }

    @Test
    void chestUnderfootStaysAlone() {
        assertTrue(inAShard(false, BlockFace.TOP).air(), "a bare click on the chest you stand on is a use");
    }

    @Test
    void standingClickPlacesNothing() {
        assertTrue(inAShard(false, BlockFace.NORTH).air(), "a bare click is a use, not a placement");
    }

    @Test
    void sneakingClickPlaces() {
        assertTrue(inAShard(true, BlockFace.NORTH).compare(Block.CHEST), "sneaking places off the chest");
    }

    /** The ground under the placer's own feet, with a chest beside: a vanilla 1.8 client refuses it before sending,
     *  and a client that sends it anyway gets the same refusal here. No use on the ground, so sneaking is moot. */
    @Test
    void ownCellRefusesChest() {
        Vec chest = new Vec(2520, 64, 2500);
        Vec own = new Vec(2521, 64, 2500);
        EventNode<Event> routing = Shard.blockRouting();
        MinecraftServer.getGlobalEventHandler().addChild(routing);
        instance.loadChunk(2520 >> 4, 2500 >> 4).join();
        Shard shard = new Shard(instance);
        FakePlayer placer = arm(new Pos(2521.5, 64, 2500.5), "OwnCellChest", false);
        try {
            shard.join(placer.player);
            shard.setBlock(chest, Block.CHEST.withProperty("facing", "north"));
            MinecraftServer.getGlobalEventHandler().call(new PlayerPacketEvent(placer.player,
                    click(own.sub(0, 1, 0), BlockFace.TOP))); // the ground under them, top face
            MinecraftServer.getSchedulerManager().processTickEnd();
            assertTrue(shard.getBlock(own).air(), "nothing lands in the placer's own cell");
            assertTrue("single".equals(shard.getBlock(chest).getProperty("type")), "and the chest beside stays single");
        } finally {
            Polyp.getInstance().profiles().setPlayer(placer.player, null);
            placer.player.remove();
            shard.reset();
            MinecraftServer.getGlobalEventHandler().removeChild(routing);
        }
    }

    @Test
    void baseLayerClickPlacesNothing() {
        assertTrue(outsideAnyShard(false).air(), "the base listener consults the registered rule too");
    }

    @Test
    void baseLayerSneakPlaces() {
        assertTrue(outsideAnyShard(true).compare(Block.CHEST), "and still places by sneaking");
    }

    private static ClientPlayerBlockPlacementPacket click(Vec chest, BlockFace face) {
        return new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, chest, face, 0.5f, 0.5f, 0.5f, false, false, 1);
    }

    /** A 1.8 placer on the mmc18 layer. */
    private static FakePlayer arm(Pos at, String name, boolean sneaking) {
        FakePlayer placer = FakePlayer.connect(instance, at, name);
        Polyp.getInstance().clientInfo().setProtocol(placer.player, 47);
        Polyp.getInstance().profiles().setPlayer(placer.player, MechanicsProfile.builder()
                .set(MechanicsKeys.COMPAT, Preset.MMC18.compat()).build());
        placer.player.setItemInMainHand(ItemStack.of(Material.CHEST));
        placer.player.setSneaking(sneaking);
        return placer;
    }

    /** What lands off a shard's chest after a member's click on {@code face}: above it for TOP (the member stands
     *  on the chest), north of it otherwise. */
    private Block inAShard(boolean sneaking, BlockFace face) {
        Vec chest = new Vec(2500, 64, 2500);
        Vec off = chest.relative(face);
        // on the chest for TOP; two cells north otherwise, clear of the cell the chest lands in
        Pos stand = face == BlockFace.TOP ? new Pos(2500.5, 64.875, 2500.5) : new Pos(2500.5, 64, 2497.5);
        EventNode<Event> routing = Shard.blockRouting();
        MinecraftServer.getGlobalEventHandler().addChild(routing);
        instance.loadChunk(2500 >> 4, 2500 >> 4).join();
        Shard shard = new Shard(instance);
        FakePlayer placer = arm(stand, (sneaking ? "ChestSneaker" : "ChestClicker") + face.name().charAt(0), sneaking);
        try {
            shard.join(placer.player);
            shard.setBlock(chest, Block.CHEST.withProperty("facing", "north"));
            MinecraftServer.getGlobalEventHandler().call(new PlayerPacketEvent(placer.player, click(chest, face)));
            MinecraftServer.getSchedulerManager().processTickEnd();
            return shard.getBlock(off);
        } finally {
            Polyp.getInstance().profiles().setPlayer(placer.player, null);
            placer.player.remove();
            shard.reset();
            MinecraftServer.getGlobalEventHandler().removeChild(routing);
        }
    }

    /** The same click from a player in no shard (the lobby): the base listener answers. */
    private Block outsideAnyShard(boolean sneaking) {
        Vec chest = new Vec(2510, 64, 2500);
        Vec off = new Vec(2510, 64, 2499);
        instance.loadChunk(2510 >> 4, 2500 >> 4).join();
        FakePlayer placer = arm(new Pos(2510.5, 64, 2497.5), sneaking ? "BaseChestSneaker" : "BaseChestClicker", sneaking);
        try {
            instance.setBlock(chest, Block.CHEST.withProperty("facing", "north"));
            Shard.placementListener(click(chest, BlockFace.NORTH), placer.player);
            MinecraftServer.getSchedulerManager().processTickEnd();
            return instance.getBlock(off);
        } finally {
            Polyp.getInstance().profiles().setPlayer(placer.player, null);
            instance.setBlock(off, Block.AIR);
            instance.setBlock(chest, Block.AIR);
            placer.player.remove();
        }
    }
}
