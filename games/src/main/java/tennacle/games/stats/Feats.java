package tennacle.games.stats;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.polyp.api.event.blocking.BlockingDamageEvent;
import io.github.term4.polyp.api.event.consume.ConsumeAppliedEvent;
import io.github.term4.polyp.api.event.damage.DamageAppliedEvent;
import io.github.term4.polyp.api.event.explosion.TntPrimeEvent;
import io.github.term4.polyp.api.event.knockback.KnockbackAppliedEvent;
import net.minestom.server.ServerFlag;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.potion.PotionEffect;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/**
 * Fighting and item use, player against player: damage in tenths of a health point, hits, blocks, throws, drinks.
 *
 * <p>A jump reset is the victim's client jumping on the first tick the knockback reaches it, still on the ground
 * (MCP-919 EntityLivingBase.onLivingUpdate): its first rise off the ground is the jump's 0.42, not the knockback's
 * own, and the next move goes on with the jump's arc. A jump made before the knockback arrived rises 0.42 too, but
 * the knockback then takes over its next move. A knockback that reached them in the air is no chance at all.
 */
public final class Feats implements GameModule {

    private static final double JUMP_RISE = 0.42;
    private static final double CLOSE = 0.002;
    private static final long WATCH_TICKS = 40;

    private record Watch(double hitRise, double jumpRise, long since, boolean jumped) {}

    private final Map<UUID, Watch> resets = new ConcurrentHashMap<>();
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
        game.on(KnockbackAppliedEvent.class, KnockbackAppliedEvent::target, e -> {
            if (!e.snapshot().melee() || !(e.target() instanceof Player victim) || !seated(victim)) return;
            int boost = victim.getEffectLevel(PotionEffect.JUMP_BOOST);
            double jump = JUMP_RISE + (boost < 0 ? 0 : (boost + 1) * 0.1);
            resets.put(victim.getUuid(), new Watch(e.velocity().y() / ServerFlag.SERVER_TICKS_PER_SECOND, jump, game.tick(), false));
        });
        game.on(PlayerMoveEvent.class, PlayerMoveEvent::getPlayer, this::moved);
        game.on(ConsumeAppliedEvent.class, ConsumeAppliedEvent::user, e -> {
            if (!seated(e.user())) return;
            ItemStack item = e.item();
            if (item.material() == Material.GOLDEN_APPLE) Tally.add(game, e.user().getUuid(), "golden-apples-eaten", 1);
            else if (item.material() == Material.POTION) drank(e.user(), item);
        });
    }

    private void moved(PlayerMoveEvent e) {
        Player player = e.getPlayer();
        Watch watch = resets.get(player.getUuid());
        if (watch == null) return;
        if (game.tick() - watch.since() > WATCH_TICKS) {
            resets.remove(player.getUuid());
            return;
        }
        double dy = e.getNewPosition().y() - player.getPosition().y();
        if (!watch.jumped()) {
            if (dy <= CLOSE) return; // moves sent before the knockback reached them
            resets.remove(player.getUuid());
            if (!player.isOnGround()) return; // it reached them in the air
            if (close(dy, watch.jumpRise()) && !close(watch.hitRise(), watch.jumpRise())) {
                resets.put(player.getUuid(), new Watch(watch.hitRise(), watch.jumpRise(), watch.since(), true));
            } else if (close(dy, watch.hitRise())) {
                Tally.add(game, player.getUuid(), "jump-reset-chances", 1);
            }
            return;
        }
        resets.remove(player.getUuid());
        if (close(dy, (watch.jumpRise() - 0.08) * 0.98)) {
            Tally.add(game, player.getUuid(), "jump-resets", 1);
            Tally.add(game, player.getUuid(), "jump-reset-chances", 1);
        }
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < CLOSE;
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
