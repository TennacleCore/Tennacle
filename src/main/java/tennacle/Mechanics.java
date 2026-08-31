package tennacle;

import tennacle.bridge.ReplayBridge;
import tennacle.bridge.ShardBridge;

import io.github.term4.archipelago.replay.core.StateChannels;
import io.github.term4.archipelago.storage.EntityDescriptors;
import io.github.term4.polyp.MechanicsKeys;
import io.github.term4.polyp.Polyp;
import io.github.term4.polyp.mechanics.attack.AttackSystem;
import io.github.term4.polyp.mechanics.attribute.AttributeSystem;
import io.github.term4.polyp.mechanics.blocking.BlockingSystem;
import io.github.term4.polyp.mechanics.consumable.ConsumableSystem;
import io.github.term4.polyp.mechanics.damage.DamageSystem;
import io.github.term4.polyp.mechanics.explosion.ExplosionSystem;
import io.github.term4.polyp.mechanics.itemdamage.ItemDamageSystem;
import io.github.term4.polyp.mechanics.hunger.HungerSystem;
import io.github.term4.polyp.mechanics.knockback.KnockbackSystem;
import io.github.term4.polyp.mechanics.projectile.ProjectileSystem;
import io.github.term4.polyp.platform.compatibility.Compat18;
import io.github.term4.polyp.platform.fixes.Fixes18;
import io.github.term4.polyp.platform.fixes.FixesSystem;
import io.github.term4.polyp.presets.Preset;
import io.github.term4.polyp.entity.PrimedTnt;
import io.github.term4.polyp.entity.DroppedItemEntity;
import io.github.term4.polyp.entity.FallingBlockEntity;
import io.github.term4.polyp.vri.Vri;
import io.github.term4.polyp.vri.VriConfig;
import io.github.term4.polyp.world.MechanicsWorld;
import net.minestom.server.entity.Player;

/**
 * The mechanics half of the playground: the profile, every polyp system, and the archipelago bridges.
 */
public final class Mechanics {

    /** The active preset - swap this ONE line to change the whole setup (mechanics profile + the primed-TNT entity, together). */
    public static final Preset PRESET = Preset.MMC18;

    public static ExplosionSystem explosions;
    public static ProjectileSystem projectiles;
    public static Polyp polyp;

    private Mechanics() {}

    public static Preset presetFor(Player p) {
        return PRESET;
    }

    /** 1.8 mechanics + the full compat/fixes layer over shards: the two bridges are the whole integration. */
    public static void install() {
        polyp = Polyp.getInstance();
        polyp.viaProxyDetails = true;
        polyp.init();
        polyp.profiles().setGlobal(PRESET.profile().toBuilder()
                // animatiumDebug: chat-narrate every set_server_features send (and why one was skipped)
                .set(MechanicsKeys.COMPAT, Compat18.config().toBuilder().animatiumDebug(true).build())
                .set(MechanicsKeys.FIXES, Fixes18.config())
                .build());
        AttackSystem.install(polyp);
        DamageSystem.install(polyp);
        KnockbackSystem.install(polyp);
        projectiles = ProjectileSystem.install(polyp);
        AttributeSystem.install(polyp);
        ConsumableSystem.install(polyp);
        BlockingSystem.install(polyp);
        HungerSystem.install(polyp);
        FixesSystem.install(polyp); // legacy-client fix set (targeted placement rollback, self-place, equipment, ...)
        Vri.install(polyp, VriConfig.all()); // crack overlay, break FX, drops, pickup - all shard-aware
        explosions = ExplosionSystem.install(polyp);
        ItemDamageSystem.install(polyp); // ground loot: explosions, fire, lava, cactus, void

        // vanilla placement rules (stairs, slabs, fences, doors, ...): registered on the BlockManager, so
        // both plain instances and shard-routed placements resolve them
        org.everbuild.blocksandstuff.blocks.BlockPlacementRuleRegistrations.registerDefault();

        ShardBridge.install();
        // save/load glue for mechanics entities: the codec reads archipelago's ENTITY_SAVE natively; mechanics stamps
        // its own mirror, so point the save side at it and bind the load-side revivers
        EntityDescriptors.describeEntity(e -> {
            var stamp = e.getTag(MechanicsWorld.ENTITY_SAVE);
            return stamp != null ? stamp.get() : null;
        });
        EntityDescriptors.reviveEntity("polyp:item", DroppedItemEntity::fromSave);
        EntityDescriptors.reviveEntity("polyp:falling_block", FallingBlockEntity::fromSave);
        EntityDescriptors.reviveEntity("polyp:projectile", projectiles::fromSave);
        EntityDescriptors.reviveEntity("polyp:tnt", d -> PrimedTnt.fromSave(explosions, d));
        ReplayBridge.install(); // replay-side mechanics glue (launch capture, shooter scrub, footage flags)
        StateChannels.register(); // opt-in player-state channels (health/hunger/xp/inventory) - /replay state
    }
}
