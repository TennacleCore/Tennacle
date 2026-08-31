package io.github.term4.archipelago.testsupport;

import io.github.term4.polyp.Polyp;
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
            ShardBridge.install();
        }
        return polyp;
    }
}
