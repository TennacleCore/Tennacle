package tennacle.games.stats;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.polyp.api.event.blocking.BlockingDamageEvent;
import io.github.term4.polyp.api.event.consume.ConsumeAppliedEvent;
import io.github.term4.polyp.api.event.damage.DamageAppliedEvent;
import io.github.term4.polyp.api.event.explosion.TntPrimeEvent;
import io.github.term4.polyp.api.event.projectile.ProjectileLaunchEvent;
import io.github.term4.polyp.mechanics.projectile.types.Arrow;
import io.github.term4.polyp.mechanics.projectile.types.Fireball;
import io.github.term4.polyp.mechanics.projectile.types.Pearl;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.PotionContents;
import net.minestom.server.potion.CustomPotionEffect;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;

/** Fighting and item use, player against player: damage in tenths of a health point, hits, blocks, throws, drinks. */
public final class Feats implements GameModule {

    private Game game;

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        game.on(DamageAppliedEvent.class, DamageAppliedEvent::target, e -> {
            if (!e.outcome().landed() || !(e.target() instanceof Player victim) || !(e.source() instanceof Player by)) return;
            if (by == victim || !seated(by) || !seated(victim)) return;
            long tenths = Math.round(e.dealt() * 10);
            Tally.add(game, by.getUuid(), "damage-dealt", tenths);
            Tally.add(game, victim.getUuid(), "damage-taken", tenths);
            Tally.pair(game, by.getUuid(), victim.getUuid().toString(), "damage-dealt", tenths);
            switch (Cause.of(e.type(), by)) {
                case MELEE -> {
                    Tally.add(game, by.getUuid(), "hits", 1);
                    Tally.add(game, victim.getUuid(), "hits-taken", 1);
                }
                case BOW -> Tally.add(game, by.getUuid(), "arrows-hit", 1);
                default -> {}
            }
        });
        game.on(BlockingDamageEvent.class, BlockingDamageEvent::defender, e -> {
            if (!(e.attacker() instanceof Player) || !seated(e.defender())) return;
            Tally.add(game, e.defender().getUuid(), "hits-blocked", 1);
            Tally.add(game, e.defender().getUuid(), "damage-blocked", Math.round((e.originalAmount() - e.blockedAmount()) * 10));
        });
        // after the throwing items' own checks: a refused throw is cancelled by now
        game.on(ProjectileLaunchEvent.class, ProjectileLaunchEvent::shooter, e -> {
            if (e.isCancelled() || !(e.shooter() instanceof Player thrower) || !seated(thrower)) return;
            var type = e.snapshot().type();
            String stat = type == Arrow.INSTANCE ? "arrows-shot" : type == Pearl.INSTANCE ? "pearls-thrown"
                    : type == Fireball.INSTANCE ? "fireballs-thrown" : null;
            ItemStack item = e.snapshot().item();
            if (stat == null && item != null && item.material() == Material.EGG) stat = "bridge-eggs-thrown";
            if (stat != null) Tally.add(game, thrower.getUuid(), stat, 1);
        });
        game.on(TntPrimeEvent.class, TntPrimeEvent::igniter, e -> {
            if (e.cause() == TntPrimeEvent.Cause.PLACEMENT && e.igniter() instanceof Player placer && seated(placer)) {
                Tally.add(game, placer.getUuid(), "tnt-placed", 1);
            }
        });
        game.on(ConsumeAppliedEvent.class, ConsumeAppliedEvent::user, e -> {
            if (!seated(e.user())) return;
            ItemStack item = e.item();
            if (item.material() == Material.GOLDEN_APPLE) Tally.add(game, e.user().getUuid(), "golden-apples-eaten", 1);
            else if (item.material() == Material.POTION) drank(e.user(), item);
        });
    }

    private void drank(Player player, ItemStack potion) {
        Tally.add(game, player.getUuid(), "potions-drunk", 1);
        PotionContents contents = potion.get(DataComponents.POTION_CONTENTS);
        if (contents == null) return;
        Set<String> names = new HashSet<>();
        for (CustomPotionEffect effect : contents.customEffects()) names.add(name(effect.id().key().value()));
        if (contents.potion() != null) names.add(name(contents.potion().key().value()));
        names.remove(null);
        for (String name : names) Tally.add(game, player.getUuid(), name + "-potions-drunk", 1);
    }

    // an effect's id or a potion's (jump_boost, leaping)
    private static String name(String kind) {
        if (kind.contains("jump") || kind.contains("leaping")) return "jump";
        if (kind.contains("speed") || kind.contains("swift")) return "speed";
        return kind.contains("invisib") ? "invisibility" : null;
    }

    private boolean seated(Player player) {
        return game.roster().slotOf(player) != null;
    }
}
