package io.github.term4.archipelago.testsupport;

import io.github.term4.polyp.Polyp;
import io.github.term4.polyp.vri.VriConfig;
import io.github.term4.polyp.vri.Vri;
import io.github.term4.polyp.MechanicsProfile;
import io.github.term4.polyp.MechanicsKeys;
import io.github.term4.polyp.mechanics.attribute.AttributeSystem;
import io.github.term4.polyp.mechanics.damage.DamageSystem;
import io.github.term4.polyp.mechanics.knockback.KnockbackSystem;
import io.github.term4.polyp.presets.vanilla18.Attributes;
import io.github.term4.polyp.presets.vanilla18.Damage;
import io.github.term4.polyp.presets.vanilla18.Knockback;
import tennacle.bridge.ShardBridge;

/** The vanilla18 combat systems + bridge, installed once per JVM - shared by every bridge test class. */
public final class MechanicsBoot {

    private static Polyp polyp;

    private MechanicsBoot() {}

    public static synchronized Polyp ensure() {
        if (polyp == null) {
            polyp = Polyp.getInstance();
            polyp.init();
            DamageSystem.install(polyp, Damage.config());
            KnockbackSystem.install(polyp, Knockback.melee());
            AttributeSystem.install(polyp, Attributes.config());
            // as the server does: in the profile AND handed to the system, so a mode's vri/... path edit layers
            // over the whole config instead of building a sparse one (VRI reads an unset knob as off)
            VriConfig vri = VriConfig.all();
            MechanicsProfile global = polyp.profiles().global();
            polyp.profiles().setGlobal((global == null ? MechanicsProfile.builder() : global.toBuilder())
                    .set(MechanicsKeys.VRI, vri).build());
            Vri.install(polyp, vri);
            ShardBridge.install();
        }
        return polyp;
    }
}
