package tennacle.looks;

import net.kyori.adventure.util.RGBLike;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.metadata.projectile.FireworkRocketMeta;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.FireworkExplosion;
import net.minestom.server.item.component.FireworkList;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;

/** A rocket that bursts where it stands, a star and a creeper in the given colors, and is gone. */
public final class Fireworks {

    private static final byte BURST = 17;

    private Fireworks() {}

    public static void burst(@NotNull Instance instance, @NotNull Pos at, @NotNull List<? extends RGBLike> colors) {
        List<RGBLike> palette = List.copyOf(colors);
        ItemStack charge = ItemStack.of(Material.FIREWORK_ROCKET).with(DataComponents.FIREWORKS, new FireworkList(1, List.of(
                new FireworkExplosion(FireworkExplosion.Shape.STAR, palette, List.of(), false, false),
                new FireworkExplosion(FireworkExplosion.Shape.CREEPER, palette, List.of(), false, false))));
        Entity rocket = new Entity(EntityType.FIREWORK_ROCKET);
        ((FireworkRocketMeta) rocket.getEntityMeta()).setFireworkInfo(charge);
        rocket.setInstance(instance, at).thenRun(() -> {
            rocket.triggerStatus(BURST);
            rocket.scheduleRemove(Duration.ofMillis(100));
        });
    }
}
