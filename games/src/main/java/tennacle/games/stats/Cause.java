package tennacle.games.stats;

import io.github.term4.polyp.mechanics.damage.types.DamageType;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** How a death came about, as the stats split it ({@code melee-kills}, {@code void-final-deaths}). */
public enum Cause {
    MELEE, BOW, VOID, FALL, FIRE, EXPLOSION, CREATURE, OTHER;

    public @NotNull String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** A creature's blow is the creature's whatever its type; a fireball's or TNT's is the explosion's. */
    public static @NotNull Cause of(@Nullable DamageType type, @Nullable Entity source) {
        if (source instanceof LivingEntity && !(source instanceof Player)) return CREATURE;
        if (type == null) return OTHER;
        return switch (type.minecraftType().key().value()) {
            case "player_attack" -> MELEE;
            case "arrow" -> BOW;
            case "mob_attack", "mob_attack_no_aggro" -> CREATURE;
            case "fall" -> FALL;
            case "in_fire", "on_fire", "lava", "hot_floor", "campfire" -> FIRE;
            case "explosion", "player_explosion", "fireball", "unattributed_fireball" -> EXPLOSION;
            case "out_of_world" -> VOID;
            default -> OTHER;
        };
    }
}
