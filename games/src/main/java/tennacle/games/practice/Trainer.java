package tennacle.games.practice;

import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.Markers;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.polyp.api.event.damage.DamageAppliedEvent;
import io.github.term4.polyp.api.event.damage.DamageEvent;
import io.github.term4.polyp.mechanics.attack.AttackSnapshot;
import io.github.term4.polyp.mechanics.attack.AttackSystem;
import io.github.term4.polyp.platform.player.PolypPlayer;
import net.minestom.server.collision.BoundingBox;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.LivingEntity;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tennacle.Press;
import tennacle.boot.Mechanics;
import tennacle.games.LobbyCommands;
import tennacle.games.practice.Drill.Knob;
import tennacle.games.stats.KnockReader;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

/**
 * One player and their bot: the hotbar tools, the bot's fight, and a line after each of its hits.
 *
 * <p>The bot plays as a 1.8 client over {@link Lag}. It traces at the start of its tick from its own eyes
 * (Minecraft.runTick: getMouseOver(1.0F)) to the player as its client draws them: what viewers were last sent, a
 * one-way trip late, glided a third of the remaining way each tick (EntityOtherPlayerMP, three increments). A target
 * flying away is drawn closer, so a chasing bot reaches further, as a player does. Its clicks reach the server a
 * one-way trip later and face the server's reach gate there, and its own knockback reaches it late. Each click is a
 * 1.8 attack: on a player, while sprinting or with a Knockback item, it cuts the bot's own motion to 0.6 whether or
 * not it lands.
 */
final class Trainer implements GameModule {

    private static final Logger LOGGER = LoggerFactory.getLogger(Trainer.class);

    // 1.8: a hit lands again once the victim's hurt time halves
    private static final int HURT_TICKS = 10;
    private static final double FLOOR_DROP = 10;
    // EntityLivingBase.getCollisionBorderSize, the client's hitbox grown for a click
    private static final double BORDER = 0.1;
    // a 1.8 client's own reach for a click to find an entity
    private static final double CLIENT_REACH = 3.0;
    // the most "until it lands" waits on the bot's own fall
    private static final int UNTIL_LANDED = 30;
    // a missed swing, tried again at about 10 CPS
    private static final int RETRY_TICKS = 2;
    // blocks a tick away from the bot: the victim is already flying, so a walking hit keeps the combo close
    private static final double FLYING_AWAY = 0.15;
    private static final int SEEN = 64;
    // EntityOtherPlayerMP.setPositionAndRotation2: a relayed move is glided over three ticks
    private static final int GLIDE = 3;
    // past this a client takes the move as a teleport
    private static final double SNAP = 8;

    private record Send(long at, boolean sprint, boolean approach, String kind) {}

    private final Random random = new Random();
    private final Lag lag = new Lag(random);
    private final long[] seenAt = new long[SEEN];
    private final Pos[] seen = new Pos[SEEN];
    private final ArrayDeque<Send> sends = new ArrayDeque<>();
    private @Nullable Pos received, view;
    private int increments;
    private Game game;
    private Pos home = Pos.ZERO, middle = Pos.ZERO;
    private @Nullable Player player;
    private @Nullable Bot bot;
    private int equipped = -1;

    private boolean running, approaching;
    private int landed, reported;
    private long readyAt, inReachSince = -1;
    private double reach;
    private boolean swingLanded;
    private long exchangeFrom, playerHitAt = -1;

    private boolean knocked;
    private long knockedAt = -1;
    private final TreeSet<Integer> clicks = new TreeSet<>();
    private int counterTick = -1, lastPlanned;
    private boolean jumpPlanned, untilLanded, leftGround, flying, reducedThisKnock;
    private int chain, planMiss, planRate, cuts, combo;
    private long lastLandedAt = Long.MIN_VALUE / 2;
    private Pos pushFrom = Pos.ZERO;
    private double pushFar, pushSize, pushX, pushZ;
    private long lastReduceAt = Long.MIN_VALUE / 2;
    private long flightFrom;

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
                String line = Lines.hit(knock.victim(), ++reported, knock, first);
                LOGGER.info("practice {}: {}", knock.victim().getUsername(), line);
            }
        });
    }

    @Override
    public void onJoin(@NotNull Game game, @NotNull Player joined, @NotNull Slot slot, boolean reclaimed) {
        player = joined;
        if (bot == null) {
            bot = Mechanics.mobs.spawn(new Bot(Mechanics.mobs, new Bot.Brain() {
                @Override
                public void think(Bot self) {
                    Trainer.this.think(self);
                }

                @Override
                public void knocked(Bot self) {
                    knocked = true;
                }

                @Override
                public void arrive(Bot self) {
                    Player target = player;
                    if (target != null && target.getInstance() == self.getInstance()) {
                        Trainer.this.arrive(self, target, Drill.of(target), game.tick());
                    }
                }
            }, lag, game::tick), game.shard(), facing(middle, home));
        }
        changed(joined);
        Lines.intro(joined);
    }

    @Override
    public void onLeave(@NotNull Game game, @NotNull Player left, @NotNull Game.Departure departure) {
        if (left == player) player = null;
    }

    @Override
    public void onEnd(@NotNull Game game, @NotNull Outcome outcome) {
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
        approaching = false;
        landed = 0;
        reported = 0;
        readyAt = game.tick();
        inReachSince = -1;
        reach = drill.reach(random);
        exchangeFrom = game.tick();
        playerHitAt = -1;
        LOGGER.info("practice {}: start, {}", who.getUsername(), Drill.encode(drill));
    }

    private void reset() {
        running = false;
        approaching = false;
        sends.clear();
        lag.clear();
        received = null;
        view = null;
        knockedAt = -1;
        knocked = false;
        flying = false;
        if (bot == null) return;
        bot.place(facing(middle, player != null ? player.getPosition() : home));
    }

    // effects follow the settings at once; the rest is read each tick
    private void changed(Player who) {
        Drill drill = Drill.of(who);
        effect(who, PotionEffect.SPEED, drill.get(Knob.SPEED));
        effect(who, PotionEffect.SLOWNESS, drill.get(Knob.SLOWNESS));
        if (bot != null) effect(bot, PotionEffect.SPEED, drill.get(Knob.BOT_SPEED));
    }

    private static void effect(LivingEntity entity, PotionEffect effect, int level) {
        entity.removeEffect(effect);
        if (level > 0) entity.addEffect(new Potion(effect, level - 1, Potion.INFINITE_DURATION));
    }

    private void damaged(DamageAppliedEvent e) {
        if (!e.outcome().landed()) return;
        if (e.source() == bot && e.target() == player) swingLanded = true;
        else if (e.source() == player && e.target() == bot) playerHitAt = game.tick();
    }

    // the bot's one tick: what reached the server, then its client's input before it moves
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
        long now = game.tick();
        Drill drill = Drill.of(target);
        lag.tick(drill);
        remember(target, now);
        equip(self, drill);

        int behind = lag.expectedUp();
        receive(seen(now - behind, target));
        Pos view = this.view;
        aim(self, view, target);
        double distance = reachTo(self, view, target.getBoundingBox());
        boolean clicked = false;

        if (knocked) {
            knocked = false;
            plan(drill, now);
        }
        boolean reducing = knockedAt >= 0;
        boolean tickZero = reducing && now == knockedAt;
        if (flying) {
            Pos body = self.body();
            pushFar = Math.max(pushFar, (body.x() - pushFrom.x()) * pushX + (body.z() - pushFrom.z()) * pushZ);
            if (self.isOnGround() && now > flightFrom || now - flightFrom > UNTIL_LANDED) {
                flying = false;
                LOGGER.info("practice {}: bot pushed {} blocks off a {} knockback, {} cuts", target.getUsername(),
                        String.format(java.util.Locale.ROOT, "%.2f", pushFar), String.format(java.util.Locale.ROOT, "%.3f", pushSize), cuts);
            }
        }
        if (reducing) {
            int rel = (int) (now - knockedAt);
            boolean counter = rel == counterTick;
            if (clicks.contains(rel) || counter) {
                clicked = true;
                if (counter || random.nextInt(100) >= planMiss) {
                    boolean found = click(self, distance, drill, counter ? "counter" : "reduce", false, true, now);
                    if (found && !counter) reduced(now);
                } else {
                    self.swingMainHand();
                }
            }
            if (!self.isOnGround()) leftGround = true;
            boolean done = rel >= lastPlanned && (!untilLanded || leftGround && self.isOnGround() || rel >= UNTIL_LANDED);
            if (done) knockedAt = -1;
        }

        boolean sprint = true;
        boolean forward = running || reducing;
        // in its own knockback only Reduce and Counter click: any other click would cut it uninvited
        if (running && !clicked && !flying) {
            boolean selfFirst = playerHitAt >= exchangeFrom;
            boolean waiting = drill.on(Knob.HIT_SELECT) && !selfFirst && !patienceOut(drill, distance, now);
            boolean ready = now >= readyAt + behind && !approaching;
            if (ready && distance <= reach && !waiting) {
                boolean sprintHit = sprintHit(drill, self, target, now);
                if (!sprintHit) self.setSprinting(false);
                sprint = sprintHit;
                // still sprinting, but a missed w-tap: the server never heard it start again
                int step = now - lastLandedAt <= drill.get(Knob.COMBO_GAP) ? combo : 0;
                boolean rearmed = !sprintHit || random.nextInt(100) >= step * drill.get(Knob.COMBO_SPRINT);
                click(self, distance, drill, !sprintHit ? "walking hit" : rearmed ? "sprint hit" : "sprint hit, not re-armed",
                        true, rearmed, now);
            } else if (waiting && distance <= reach) {
                forward = false;
            } else if (drill.hitType() == Drill.HitType.SPACING && !ready && distance < reach + 0.5) {
                forward = false;
            }
        }
        if (jumpPlanned && tickZero) {
            jumpPlanned = false;
            if (self.isOnGround()) {
                self.setSprinting(true);
                self.jumpNow();
            }
        }
        self.setSprinting(forward && sprint);
        self.walk(forward);
        glide();
    }

    private void receive(Pos arriving) {
        if (received != null && arriving.samePoint(received)) return;
        if (view == null || arriving.distance(view) > SNAP) view = arriving;
        received = arriving;
        increments = GLIDE;
    }

    // after the trace: the client's other players move in its entity update
    private void glide() {
        if (increments <= 0 || view == null || received == null) return;
        Vec step = received.sub(view).asVec().div(increments--);
        view = view.add(step);
    }

    // a click: the client-side cut now, the attack at the server a one-way trip later
    // whether the client found the player under its crosshair
    private boolean click(Bot self, double distance, Drill drill, String kind, boolean approach, boolean rearmed, long now) {
        self.swingMainHand();
        if (distance > Math.max(CLIENT_REACH, drill.get(Knob.REACH_MAX) / 4.0)) return false;
        boolean sprinting = self.isSprinting();
        if (sprinting || drill.get(Knob.KNOCKBACK) > 0) {
            if (flying) cuts++;
            self.cut();
            self.setSprinting(false);
        }
        if (approach) approaching = true;
        sends.add(new Send(lag.up(now), sprinting && rearmed, approach, kind));
        return true;
    }

    // one reduced knockback is one step of the chain, however many clicks it took
    private void reduced(long now) {
        lastReduceAt = now;
        if (reducedThisKnock) return;
        reducedThisKnock = true;
        chain++;
    }

    private void arrive(Bot self, Player target, Drill drill, long now) {
        AttackSystem attacks = Mechanics.polyp.module(AttackSystem.class);
        while (!sends.isEmpty() && sends.peek().at() <= now) {
            Send send = sends.poll();
            if (send.approach()) approaching = false;
            if (attacks == null) continue;
            boolean client = self.isSprinting();
            self.setSprinting(send.sprint()); // the server reads the sprint the packet carried
            swingLanded = false;
            attacks.apply(new AttackSnapshot(self, target, null));
            self.setSprinting(client);
            if (!swingLanded) {
                if (send.approach()) readyAt = now + RETRY_TICKS;
                continue;
            }
            landed++;
            combo = now - lastLandedAt <= drill.get(Knob.COMBO_GAP) ? combo + 1 : 1;
            lastLandedAt = now;
            readyAt = now + HURT_TICKS + drill.ticks(Knob.WAIT_MIN, Knob.WAIT_MAX, Knob.WAIT_LEAN, random);
            reach = drill.reach(random);
            inReachSince = -1;
            LOGGER.info("practice {}: bot {} landed, {} blocks, ping {} ms", target.getUsername(), send.kind(),
                    String.format(java.util.Locale.ROOT, "%.2f", reachTo(self, target.getPosition(), target.getBoundingBox())), lag.ping());
            if (!drill.on(Knob.CONTINUOUS) && landed >= drill.get(Knob.HITS)) running = false;
        }
    }

    // the bot's client just took a knockback: which of its next ticks it clicks on, and whether it jumps
    private void plan(Drill drill, long now) {
        knockedAt = now;
        flying = true;
        flightFrom = now;
        if (bot != null) {
            pushFrom = bot.body();
            pushSize = Math.hypot(bot.motion().x(), bot.motion().z());
            pushX = pushSize > 0 ? bot.motion().x() / pushSize : 0;
            pushZ = pushSize > 0 ? bot.motion().z() / pushSize : 0;
        }
        pushFar = 0;
        cuts = 0;
        clicks.clear();
        leftGround = false;
        untilLanded = drill.on(Knob.REDUCE) && drill.get(Knob.REDUCE_FOR) == 0;
        lastPlanned = 0;
        reducedThisKnock = false;
        if (now - lastReduceAt > drill.get(Knob.CHAIN_GAP)) chain = 0;
        int step = chain;
        planMiss = clamp(drill.get(Knob.REDUCE_MISS) + step * drill.get(Knob.CHAIN_MISS) + swing(drill.get(Knob.MISS_SWING)), 0, 100);
        // every tick and picked ticks are 20 a second; the chain and the swing take theirs off that
        int base = drill.pattern() == Drill.Pattern.PER_SECOND ? drill.get(Knob.REDUCE_CPS) : 20;
        planRate = clamp(base - step * drill.get(Knob.CHAIN_CPS) + swing(drill.get(Knob.CPS_SWING)), 0, 20);
        if (drill.on(Knob.REDUCE)) {
            int start = drill.ticks(Knob.REDUCE_START_MIN, Knob.REDUCE_START_MAX, Knob.REDUCE_START_LEAN, random)
                    + step * drill.get(Knob.CHAIN_DELAY);
            int length = untilLanded ? UNTIL_LANDED : drill.get(Knob.REDUCE_FOR);
            if (drill.pattern() == Drill.Pattern.CUSTOM) {
                int ticks = drill.get(Knob.REDUCE_TICKS);
                for (int b = 0; b < Drill.PATTERN_TICKS; b++) {
                    if ((ticks & 1 << b) != 0 && random.nextInt(20) < planRate) clicks.add(start + b);
                }
            } else {
                for (int t = start; t < start + length; t++) if (random.nextInt(20) < planRate) clicks.add(t);
            }
            if (!clicks.isEmpty()) lastPlanned = clicks.last();
        }
        counterTick = drill.on(Knob.COUNTER) ? drill.ticks(Knob.COUNTER_MIN, Knob.COUNTER_MAX, Knob.COUNTER_LEAN, random) : -1;
        lastPlanned = Math.max(lastPlanned, counterTick);
        jumpPlanned = random.nextInt(100) < drill.get(Knob.JUMP_RESET);
        if (player != null && bot != null) LOGGER.info("practice {}: bot knocked, {} up off a tracked {}, chain {}, miss {}%, {} CPS, clicks {}, counter {}, jump {}",
                player.getUsername(), String.format(java.util.Locale.ROOT, "%.4f", bot.sentY()),
                String.format(java.util.Locale.ROOT, "%.4f", bot.heldY()), step, planMiss, planRate, clicks, counterTick, jumpPlanned);
    }

    private boolean sprintHit(Drill drill, Bot self, Player target, long now) {
        return switch (drill.hitType()) {
            case SPRINT -> true;
            case NORMAL -> false;
            case RANDOM -> random.nextInt(100) < drill.get(Knob.SPRINT_CHANCE);
            case SPACING -> {
                int behind = lag.expectedUp();
                // relayed every other tick: the step over two
                Pos was = seen(now - behind - 2, target), is = seen(now - behind, target);
                double dx = is.x() - self.getPosition().x(), dz = is.z() - self.getPosition().z();
                double len = Math.hypot(dx, dz);
                double away = len == 0 ? 0 : ((is.x() - was.x()) * dx + (is.z() - was.z()) * dz) / len / 2;
                yield away < FLYING_AWAY;
            }
        };
    }

    private boolean patienceOut(Drill drill, double distance, long now) {
        if (distance > reach) return false;
        if (inReachSince < 0) inReachSince = now;
        int patience = drill.get(Knob.PATIENCE);
        return patience != Drill.NEVER && now - inReachSince >= patience;
    }

    // what viewers were sent this tick
    private void remember(Player target, long now) {
        int i = (int) Math.floorMod(now, (long) SEEN);
        seenAt[i] = now;
        seen[i] = relayed(target);
    }

    private Pos seen(long tick, Player target) {
        int i = (int) Math.floorMod(tick, (long) SEEN);
        return seenAt[i] == tick && seen[i] != null ? seen[i] : relayed(target);
    }

    private static Pos relayed(Player target) {
        return target instanceof PolypPlayer relayed ? relayed.relayedPosition() : target.getPosition();
    }

    private void equip(Bot self, Drill drill) {
        int kb = drill.get(Knob.KNOCKBACK);
        if (equipped == kb) return;
        equipped = kb;
        self.setItemInMainHand(kb == 0 ? ItemStack.of(Material.IRON_SWORD)
                : ItemStack.of(Material.STICK).with(DataComponents.ENCHANTMENTS, new EnchantmentList(Map.of(Enchantment.KNOCKBACK, kb))));
    }

    private static void aim(Bot self, Pos view, Player target) {
        Pos eye = self.getPosition().add(0, self.getEyeHeight(), 0);
        double dx = view.x() - eye.x(), dy = view.y() + target.getEyeHeight() - eye.y(), dz = view.z() - eye.z();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        self.yaw(yaw);
        self.headYaw(yaw);
        self.bodyYaw(yaw);
        self.pitch(pitch);
    }

    // eyes to the nearest point of the grown hitbox
    static double reachTo(Bot self, Pos at, BoundingBox box) {
        Pos eye = self.getPosition().add(0, self.getEyeHeight(), 0);
        double x = clamp(eye.x(), at.x() + box.minX() - BORDER, at.x() + box.maxX() + BORDER);
        double y = clamp(eye.y(), at.y() + box.minY() - BORDER, at.y() + box.maxY() + BORDER);
        double z = clamp(eye.z(), at.z() + box.minZ() - BORDER, at.z() + box.maxZ() + BORDER);
        return Math.sqrt((eye.x() - x) * (eye.x() - x) + (eye.y() - y) * (eye.y() - y) + (eye.z() - z) * (eye.z() - z));
    }

    private int swing(int amount) {
        return amount == 0 ? 0 : random.nextInt(2 * amount + 1) - amount;
    }

    private static int clamp(int v, int low, int high) {
        return Math.max(low, Math.min(high, v));
    }

    private static double clamp(double v, double low, double high) {
        return Math.max(low, Math.min(high, v));
    }

    private static Pos facing(Pos from, Pos to) {
        double dx = to.x() - from.x(), dz = to.z() - from.z();
        return from.withView((float) Math.toDegrees(Math.atan2(-dx, dz)), 0);
    }
}
