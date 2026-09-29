package tennacle.glue;

import io.github.term4.polyp.entity.FallingBlockEntity;
import io.github.term4.polyp.Polyp;
import io.github.term4.polyp.platform.compatibility.CompatPlacement;
import io.github.term4.polyp.platform.player.PlayerConfigApplier;
import io.github.term4.polyp.world.MechanicsWorld;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardWorlds;
import io.github.term4.archipelago.event.ShardJoinEvent;
import io.github.term4.archipelago.event.ShardLeaveEvent;
import io.github.term4.archipelago.event.ShardTransferEvent;
import net.minestom.server.MinecraftServer;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.entity.Player;
import net.minestom.server.timer.TaskSchedule;

/**
 * App glue between Archipelago and the mechanics stack. The world seam itself is first-party
 * ({@link ShardWorlds#install()}, a {@link Shard} IS a {@link MechanicsWorld}); what remains here is
 * app policy: BnS block behavior routing, break FX through the fx registry, and the player-platform
 * re-push on shard hops.
 */
public final class ShardBridge {

    private ShardBridge() {}

    /** Install once at boot, before players join (alongside {@code Shard.blockRouting()}). */
    public static synchronized void install() {
        ShardWorlds.install();
        // 1.8 clutch semantics for LEGACY placers only (ladder/vine/web unchecked, stairs into their own body);
        // modern clients keep the precise check their own prediction agrees with
        Shard.placementBodyCheck(CompatPlacement::placementBodyCheck);
        // Polyp's WorldSounds emits the break fx off the break event a routed dig fires too; the default would double it
        Shard.breakFx((breaker, shard, pos, broken) -> {});
        // door/gate/cake handlers read+write the clicker's shard, not the base map
        org.everbuild.blocksandstuff.blocks.util.InteractionWorlds.setResolver(interaction ->
                interaction.getPlayer().getTag(MechanicsWorld.ENTITY_TAG) instanceof Shard s
                        ? new io.github.term4.archipelago.shard.ShardBlockView(s) : null);
        // gravity blocks fall as Polyp's 1.8 entity into the world behind the rule's view; a routed placement's own
        // rule reads a staging view, not the shard's
        org.everbuild.blocksandstuff.blocks.util.FallingBlocks.setHandler((getter, pos, block) -> {
            MechanicsWorld world = MechanicsWorld.ofView(getter);
            if (world == null) return;
            MinecraftServer.getSchedulerManager().buildTask(() -> {
                if (!world.getBlock(pos).compare(block)) return;
                if (!FallingBlockEntity.canFallInto(world.getBlock(pos.add(0, -1, 0)))) return;
                FallingBlockEntity.spawn(world, pos, world.getBlock(pos));
            }).delay(TaskSchedule.tick((int) org.everbuild.blocksandstuff.blocks.util.FallingBlocks.FALL_DELAY_TICKS)).schedule();
        });
        // a shard hop re-points the profile scope chain with NO profile change and NO spawn, so the player-platform
        // push (simulated-TPS tick rate, compat, platform config) would keep the previous world's state
        EventNode<Event> node = EventNode.all("bridge:shards");
        node.addListener(ShardJoinEvent.class, e -> repush(e.player()));
        node.addListener(ShardLeaveEvent.class, e -> repush(e.player()));
        node.addListener(ShardTransferEvent.class, e -> {
            if (e.entity() instanceof Player p) repush(p);
        });
        MinecraftServer.getGlobalEventHandler().addChild(node);
    }

    private static void repush(Player player) {
        PlayerConfigApplier.apply(Polyp.getInstance(), player);
    }
}
