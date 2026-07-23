package test;

import io.github.term4.archipelago.replay.core.StateChannels;
import io.github.term4.archipelago.storage.EntityDescriptors;
import io.github.term4.minestommechanics.MechanicsKeys;
import io.github.term4.minestommechanics.MinestomMechanics;
import io.github.term4.minestommechanics.mechanics.attack.AttackSystem;
import io.github.term4.minestommechanics.mechanics.attribute.AttributeSystem;
import io.github.term4.minestommechanics.mechanics.blocking.BlockingSystem;
import io.github.term4.minestommechanics.mechanics.consumable.ConsumableSystem;
import io.github.term4.minestommechanics.mechanics.damage.DamageSystem;
import io.github.term4.minestommechanics.mechanics.explosion.ExplosionSystem;
import io.github.term4.minestommechanics.mechanics.hunger.HungerSystem;
import io.github.term4.minestommechanics.mechanics.knockback.KnockbackSystem;
import io.github.term4.minestommechanics.mechanics.projectile.ProjectileSystem;
import io.github.term4.minestommechanics.platform.compatibility.Compat18;
import io.github.term4.minestommechanics.platform.fixes.Fixes18;
import io.github.term4.minestommechanics.platform.fixes.FixesSystem;
import io.github.term4.minestommechanics.presets.Preset;
import io.github.term4.minestommechanics.entity.PrimedTnt;
import io.github.term4.minestommechanics.entity.DroppedItemEntity;
import io.github.term4.minestommechanics.vri.Vri;
import io.github.term4.minestommechanics.vri.VriConfig;
import io.github.term4.minestommechanics.world.MechanicsWorld;
import net.minestom.server.entity.Player;

/**
 * The mechanics half of the playground: the profile, every mm system, and the archipelago bridges.
 */
final class Mechanics {

    /** The active preset - swap this ONE line to change the whole setup (mechanics profile + the primed-TNT entity, together). */
    static final Preset PRESET = Preset.HYPIXEL;

    static ExplosionSystem explosions;
    static ProjectileSystem projectiles;
    static MinestomMechanics mm;

    private Mechanics() {}

    static Preset presetFor(Player p) {
        return PRESET;
    }

    /** 1.8 mechanics + the full compat/fixes layer over shards: the two bridges are the whole integration. */
    static void install() {
        mm = MinestomMechanics.getInstance();
        mm.viaProxyDetails = true;
        mm.init();
        mm.profiles().setGlobal(PRESET.profile().toBuilder()
                // animatiumDebug: chat-narrate every set_server_features send (and why one was skipped)
                .set(MechanicsKeys.COMPAT, Compat18.config().toBuilder().animatiumDebug(true).build())
                .set(MechanicsKeys.FIXES, Fixes18.config())
                .build());
        AttackSystem.install(mm);
        DamageSystem.install(mm);
        KnockbackSystem.install(mm);
        projectiles = ProjectileSystem.install(mm);
        AttributeSystem.install(mm);
        ConsumableSystem.install(mm);
        BlockingSystem.install(mm);
        HungerSystem.install(mm);
        FixesSystem.install(mm); // legacy-client fix set (targeted placement rollback, self-place, equipment, ...)
        Vri.install(mm, VriConfig.all()); // crack overlay, break FX, drops, pickup - all shard-aware
        explosions = ExplosionSystem.install(mm);

        ShardBridge.install();
        ShardBridge.installDomainTicking();
        // save/load glue for mechanics entities: the codec reads archipelago's ENTITY_SAVE natively; mechanics stamps
        // its own mirror, so point the save side at it and bind the load-side revivers
        EntityDescriptors.describeEntity(e -> {
            var stamp = e.getTag(MechanicsWorld.ENTITY_SAVE);
            return stamp != null ? stamp.get() : null;
        });
        EntityDescriptors.reviveEntity("mm:item", DroppedItemEntity::fromSave);
        EntityDescriptors.reviveEntity("mm:projectile", projectiles::fromSave);
        EntityDescriptors.reviveEntity("mm:tnt", d -> PrimedTnt.fromSave(explosions, d));
        ReplayBridge.install(); // replay-side mechanics glue (launch capture, shooter scrub, footage flags)
        StateChannels.register(); // opt-in player-state channels (health/hunger/xp/inventory) - /replay state
    }
}
