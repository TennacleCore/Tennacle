package tennacle.boot;

import tennacle.glue.ReplayBridge;
import tennacle.glue.ShardBridge;

import io.github.term4.archipelago.replay.core.StateChannels;
import io.github.term4.archipelago.storage.EntityDescriptors;
import io.github.term4.polyp.MechanicsKeys;
import io.github.term4.polyp.MechanicsProfile;
import io.github.term4.polyp.Polyp;
import io.github.term4.polyp.mechanics.attack.AttackSystem;
import io.github.term4.polyp.mechanics.attribute.AttributeSystem;
import io.github.term4.polyp.mechanics.blocks.BlocksSystem;
import io.github.term4.polyp.mechanics.containers.ContainerSystem;
import io.github.term4.polyp.mechanics.blocking.BlockingSystem;
import io.github.term4.polyp.mechanics.consumable.ConsumableSystem;
import io.github.term4.polyp.mechanics.damage.DamageSystem;
import io.github.term4.polyp.mechanics.explosion.ExplosionSystem;
import io.github.term4.polyp.mechanics.itemdamage.ItemDamageSystem;
import io.github.term4.grotto.modules.combat.FriendlyFire;
import io.github.term4.polyp.mechanics.projectile.entities.ProjectileEntity;
import io.github.term4.polyp.mechanics.hunger.HungerSystem;
import io.github.term4.polyp.mechanics.knockback.KnockbackSystem;
import io.github.term4.polyp.mechanics.fluids.FluidSystem;
import io.github.term4.polyp.mechanics.mobs.MobsSystem;
import io.github.term4.polyp.mechanics.projectile.ProjectileSystem;
import io.github.term4.polyp.platform.fixes.FixesLegacy;
import io.github.term4.polyp.platform.fixes.FixToggleConfig;
import io.github.term4.polyp.platform.fixes.FixesConfig;
import io.github.term4.polyp.platform.fixes.FixesSystem;
import io.github.term4.polyp.presets.Preset;
import io.github.term4.polyp.entity.PrimedTnt;
import io.github.term4.polyp.entity.DroppedItemEntity;
import io.github.term4.polyp.entity.FallingBlockEntity;
import io.github.term4.polyp.vri.Vri;
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
    public static ContainerSystem containers;
    public static MobsSystem mobs;
    public static FluidSystem fluids;
    public static Polyp polyp;

    private Mechanics() {}

    /** A preset under this server's own layer: the compat debug flag and the fix set every scope inherits. */
    public static MechanicsProfile profileOf(Preset preset) {
        return preset.profile().toBuilder()
                // animatiumDebug: chat-narrate every set_server_features send (and why one was skipped)
                .set(MechanicsKeys.COMPAT, preset.compat().toBuilder()
                        .animatiumDebug(Config.flag("tennacle.animatium-debug", false)).build())
                .set(MechanicsKeys.FIXES, FixesConfig.builder(FixesLegacy.config())
                        // OFF here, and it must stay off: the fix takes the placement listener slot the shard
                        // router owns, and its blanket placer exclusion would sit in front of the body check
                        // that already scopes self-placement per profile (Shard.placementBodyCheck)
                        .legacySelfPlacement(FixToggleConfig.of(false))
                        .build())
                .build();
    }

    /** Who is behind a damage source that is no player: a TNT's igniter, a projectile's shooter. */
    public static void attribution() {
        FriendlyFire.attribution(e -> e instanceof PrimedTnt tnt ? tnt.igniter()
                : e instanceof ProjectileEntity shot ? shot.getShooter() : null);
    }

    /** 1.8 mechanics + the full compat/fixes layer over shards: the two bridges are the whole integration. */
    /**
     * The clicks a block answers instead of taking a placement, unless sneaking (vanilla, both eras): beds, containers,
     * and the wooden doors, trapdoors and fence gates a hand opens. The shard routing resolves each by key.
     */
    public static void blockUses() {
        var vanilla = org.everbuild.blocksandstuff.blocks.group.VanillaBlockBehaviour.INSTANCE;
        org.everbuild.blocksandstuff.blocks.BlockBehaviorRuleRegistrations.register(vanilla.getBEDS(), vanilla.getCONTAINERS(),
                vanilla.getWOODEN_DOORS(), vanilla.getTRAPDOOR(), vanilla.getFENCE_GATE());
    }

    public static void install() {
        polyp = Polyp.getInstance();
        polyp.viaConnectionDetails = true; // the Via on the Velocity proxies reports each client's protocol
        polyp.installViaBridge = true; // the proxies run the ViaBridge plugin
        polyp.init();
        polyp.profiles().setGlobal(profileOf(PRESET));

        // vanilla placement rules (stairs, slabs, fences, doors, ...): registered on the BlockManager, so
        // both plain instances and shard-routed placements resolve them
        org.everbuild.blocksandstuff.blocks.BlockPlacementRuleRegistrations.registerDefault();
        blockUses();
        BlocksSystem.install(polyp); // after the rules above: the chest slot wraps the one registered there
        containers = ContainerSystem.install(polyp); // the opening itself: contents on the world, spilled on break
        AttackSystem.install(polyp);
        DamageSystem.install(polyp);
        KnockbackSystem.install(polyp);
        projectiles = ProjectileSystem.install(polyp);
        mobs = MobsSystem.install(polyp, io.github.term4.polyp.presets.vanilla18.Mobs.config());
        fluids = FluidSystem.install(polyp, io.github.term4.polyp.presets.vanilla18.Fluids.config()); // after the fork's rules: water and lava chain them
        AttributeSystem.install(polyp);
        ConsumableSystem.install(polyp);
        BlockingSystem.install(polyp);
        HungerSystem.install(polyp);
        FixesSystem.install(polyp); // legacy-client fix set (targeted placement rollback, self-place, equipment, ...)
        Vri.install(polyp); // crack overlay, break FX, drops, pickup - the preset's VriConfig, a ruleset's edits over it
        explosions = ExplosionSystem.install(polyp);
        attribution();
        ItemDamageSystem.install(polyp); // ground loot: explosions, fire, lava, cactus, void

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
