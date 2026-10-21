package tennacle.games.practice;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.Markers;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.polyp.api.event.damage.DamageAppliedEvent;
import io.github.term4.polyp.api.event.damage.DamageEvent;
import io.github.term4.polyp.mechanics.attack.AttackSnapshot;
import io.github.term4.polyp.mechanics.attack.AttackSystem;
import io.github.term4.barnacle.marker.PosMarker;
import net.minestom.server.collision.BoundingBox;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.EnchantmentList;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.Press;
import tennacle.boot.Mechanics;
import tennacle.games.LobbyCommands;
import tennacle.games.stats.KnockReader;

import java.util.List;
import java.util.Map;
import java.util.Random;

/** One player and their bot: the hotbar tools, the bot's fight, and a line after each of its hits. */
final class Trainer implements GameModule {

    // 1.8: a hit lands again once the victim's hurt time halves
    private static final int HURT_TICKS = 10;
    private static final double FLOOR_DROP = 10;
    // EntityLivingBase.getCollisionBorderSize, the client's hitbox grown for a click
    private static final double BORDER = 0.1;

    private final Random random = new Random();
    private Game game;
    private Pos home = Pos.ZERO, middle = Pos.ZERO;
    private @Nullable Player player;
    private @Nullable Bot bot;
    private int equipped = -1;
    private boolean running;
    private int landed, reported;
    private long readyAt, swingAt = -1;
    private double reach;
    private boolean swingLanded;
    private long exchangeFrom, playerHitAt = -1;

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        List<PosMarker> spawns = game.arena().markers(Markers.SPAWN);
        if (!spawns.isEmpty()) {
            home = spawns.getFirst().pos();
            Pos other = spawns.size() > 1 ? spawns.get(1).pos() : home;
            middle = new Pos((home.x() + other.x()) / 2, home.y(), (home.z() + other.z()) / 2);
        }
        game.on(DamageEvent.class, DamageEvent::target, e -> e.amount(0));
        game.on(DamageAppliedEvent.class, DamageAppliedEvent::target, this::damaged);
        game.on(PlayerUseItemEvent.class, e -> pressed(e.getPlayer(), e.getHand()));
        game.on(PlayerBlockInteractEvent.class, e -> pressed(e.getPlayer(), e.getHand()));
        game.on(PlayerMoveEvent.class, e -> {
            if (e.getNewPosition().y() < middle.y() - FLOOR_DROP) e.getPlayer().teleport(home);
        });
        KnockReader.install(game, p -> p == player, new KnockReader.Listener() {
            @Override
            public void ended(@NotNull KnockReader.Knock knock) {
                if (knock.source() == null || knock.source() != bot) return;
                boolean first = playerHitAt >= exchangeFrom && playerHitAt < knock.sentAt();
                exchangeFrom = game.tick();
                Lines.hit(knock.victim(), ++reported, knock, first);
            }
        });
    }

    @Override
    public void onJoin(@NotNull Game game, @NotNull Player joined, @NotNull Slot slot, boolean reclaimed) {
        player = joined;
        if (bot == null) {
            bot = Mechanics.mobs.spawn(new Bot(Mechanics.mobs, this::think), game.shard(), facing(middle, home));
        }
        changed(joined);
        Lines.intro(joined);
    }

    @Override
    public void onLeave(@NotNull Game game, @NotNull Player left, @NotNull Game.Departure departure) {
        if (left == player) player = null;
    }

    @Override
    public void onEnd(@NotNull Game game, @NotNull io.github.term4.grotto.Outcome outcome) {
        if (bot != null) bot.remove();
        bot = null;
    }

    private void pressed(Player who, PlayerHand hand) {
        if (hand != PlayerHand.MAIN || who != player) return;
        String tool = who.getItemInMainHand().getTag(Lines.TOOL);
        if (tool == null || !Press.once(who)) return;
        switch (tool) {
            case "start" -> start(who);
            case "reset" -> reset();
            case "settings" -> DrillMenu.open(who, this::changed);
            case "leave" -> LobbyCommands.leave(who);
            default -> {}
        }
    }

    private void start(Player who) {
        Drill drill = Drill.of(who);
        running = true;
        landed = 0;
        reported = 0;
        readyAt = game.tick();
        swingAt = -1;
        reach = drill.reach(random);
        exchangeFrom = game.tick();
        playerHitAt = -1;
    }

    private void reset() {
        running = false;
        swingAt = -1;
        if (bot == null) return;
        bot.stop();
        bot.teleport(facing(middle, player != null ? player.getPosition() : home));
    }

    // effects follow the settings at once; the rest is read each tick
    private void changed(Player who) {
        Drill drill = Drill.of(who);
        effect(who, PotionEffect.SPEED, drill.speed());
        effect(who, PotionEffect.SLOWNESS, drill.slowness());
        if (bot != null) effect(bot, PotionEffect.SPEED, drill.botSpeed());
    }

    private static void effect(net.minestom.server.entity.LivingEntity entity, PotionEffect effect, int level) {
        entity.removeEffect(effect);
        if (level > 0) entity.addEffect(new Potion(effect, level - 1, Potion.INFINITE_DURATION));
    }

    private void damaged(DamageAppliedEvent e) {
        if (!e.outcome().landed()) return;
        if (e.source() == bot && e.target() == player) swingLanded = true;
        else if (e.source() == player && e.target() == bot) playerHitAt = game.tick();
    }

    // the bot's one tick of input, before it moves: aim, swing, W
    private void think(Bot self) {
        Player target = player;
        if (target == null || target.getInstance() != self.getInstance()) {
            self.setSprinting(false);
            self.walk(false);
            return;
        }
        if (self.getPosition().y() < middle.y() - FLOOR_DROP) {
            reset();
            return;
        }
        Drill drill = Drill.of(target);
        self.latency = drill.latency();
        equip(self, drill);
        aim(self, target);
        long now = game.tick();
        if (running && swingAt < 0 && now >= readyAt - drill.latency() && reachTo(self, target) <= reach) {
            swingAt = now + drill.latency();
        }
        if (swingAt >= 0 && now >= swingAt) {
            swingAt = -1;
            swing(self, target, drill);
        }
        if (!running) {
            self.setSprinting(false);
            self.walk(false);
            return;
        }
        self.setSprinting(drill.sprint());
        self.walk(true);
    }

    private void swing(Bot self, Player target, Drill drill) {
        boolean cuts = self.isSprinting() || drill.knockback() > 0;
        swingLanded = false;
        self.swingMainHand();
        AttackSystem attacks = Mechanics.polyp.module(AttackSystem.class);
        if (attacks != null) attacks.apply(new AttackSnapshot(self, target, null));
        long now = game.tick();
        if (!swingLanded) {
            readyAt = now + 1;
            return;
        }
        // EntityPlayer.attackTargetEntityWithCurrentItem: the attacker's own motion on a sprint or Knockback hit
        if (cuts) self.motion(self.motion().mul(0.6, 1, 0.6));
        landed++;
        readyAt = now + HURT_TICKS + drill.delay(random);
        reach = drill.reach(random);
        if (!drill.endless() && landed >= drill.hits()) running = false;
    }

    private void equip(Bot self, Drill drill) {
        if (equipped == drill.knockback()) return;
        equipped = drill.knockback();
        self.setItemInMainHand(drill.knockback() == 0 ? ItemStack.of(Material.IRON_SWORD)
                : ItemStack.of(Material.STICK).with(DataComponents.ENCHANTMENTS,
                        new EnchantmentList(Map.of(Enchantment.KNOCKBACK, drill.knockback()))));
    }

    private static void aim(Bot self, Player target) {
        Pos eye = self.getPosition().add(0, self.getEyeHeight(), 0);
        Pos at = target.getPosition().add(0, target.getEyeHeight(), 0);
        double dx = at.x() - eye.x(), dy = at.y() - eye.y(), dz = at.z() - eye.z();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        self.yaw(yaw);
        self.headYaw(yaw);
        self.bodyYaw(yaw);
        self.pitch(pitch);
    }

    // eyes to the nearest point of the grown hitbox
    static double reachTo(Bot self, Player target) {
        Pos eye = self.getPosition().add(0, self.getEyeHeight(), 0);
        Pos at = target.getPosition();
        BoundingBox box = target.getBoundingBox();
        double x = clamp(eye.x(), at.x() + box.minX() - BORDER, at.x() + box.maxX() + BORDER);
        double y = clamp(eye.y(), at.y() + box.minY() - BORDER, at.y() + box.maxY() + BORDER);
        double z = clamp(eye.z(), at.z() + box.minZ() - BORDER, at.z() + box.maxZ() + BORDER);
        return Math.sqrt((eye.x() - x) * (eye.x() - x) + (eye.y() - y) * (eye.y() - y) + (eye.z() - z) * (eye.z() - z));
    }

    private static double clamp(double v, double low, double high) {
        return Math.max(low, Math.min(high, v));
    }

    private static Pos facing(Pos from, Pos to) {
        double dx = to.x() - from.x(), dz = to.z() - from.z();
        return from.withView((float) Math.toDegrees(Math.atan2(-dx, dz)), 0);
    }
}
