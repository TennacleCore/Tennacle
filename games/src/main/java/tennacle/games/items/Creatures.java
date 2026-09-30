package tennacle.games.items;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import java.util.function.BiConsumer;
import io.github.term4.grotto.modules.outcome.LastStanding;
import tennacle.boot.Mechanics;
import tennacle.games.Match;
import tennacle.games.beds.Lives;
import tennacle.games.map.Center;
import tennacle.looks.Palette;

import io.github.term4.canvas.hologram.Hologram;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.api.event.damage.PreDamageEvent;
import io.github.term4.polyp.api.event.projectile.ProjectileHitEvent;
import io.github.term4.polyp.mechanics.attribute.catalog.EffectParticles;
import io.github.term4.polyp.mechanics.mobs.MobEntity;
import io.github.term4.polyp.mechanics.mobs.MobKindConfig;
import io.github.term4.polyp.mechanics.mobs.Sides;
import io.github.term4.polyp.mechanics.mobs.Targets;
import io.github.term4.polyp.mechanics.mobs.kinds.EnderDragonEntity;
import io.github.term4.polyp.mechanics.mobs.kinds.IronGolemEntity;
import io.github.term4.polyp.mechanics.mobs.kinds.SilverfishEntity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.codec.Codec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.entity.metadata.LivingEntityMeta;
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.utils.Direction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The creatures a seat sets on the other sides (captured 2026-09-25): the Bedbug a snowball leaves where it lands,
 * the Dream Defender an egg puts down, and a side's dragon. Each fights every side but its own, wears its side's
 * name, carries a countdown over or under it, and its kills are its owner's - without the dead's items, unless
 * {@code combat.death.to-owner} says otherwise. Any mode with lives and last-standing may list it.
 */
public final class Creatures implements GameModule {

    private static final Logger LOGGER = LoggerFactory.getLogger(Creatures.class);

    public static final StateKey<Long> BEDBUG_LIFE = StateKey.of("creatures.bedbug.life", Codec.LONG, 300L);
    public static final StateKey<Double> BEDBUG_HEALTH = StateKey.of("creatures.bedbug.health", Codec.DOUBLE, 8.0);
    /** Effect levels; 0 for none. */
    public static final StateKey<Integer> BEDBUG_STRENGTH = StateKey.of("creatures.bedbug.strength", Codec.INT, 2);
    public static final StateKey<Integer> BEDBUG_RESISTANCE = StateKey.of("creatures.bedbug.resistance", Codec.INT, 1);
    public static final StateKey<Long> DEFENDER_LIFE = StateKey.of("creatures.defender.life", Codec.LONG, 4800L);
    public static final StateKey<Double> DEFENDER_HEALTH = StateKey.of("creatures.defender.health", Codec.DOUBLE, 20.0);
    /** Fitted: 0.15 to 0.16 blocks a tick chasing, against vanilla's 0.135 at 0.25. */
    public static final StateKey<Double> DEFENDER_SPEED = StateKey.of("creatures.defender.speed", Codec.DOUBLE, 0.27);
    /** Ticks between swings in reach; the hurt window paces what lands. */
    public static final StateKey<Integer> DEFENDER_SWING = StateKey.of("creatures.defender.swing", Codec.INT, 1);
    public static final StateKey<Double> DRAGONS_LIFT = StateKey.of("creatures.dragons.lift", Codec.DOUBLE, 0.4);
    /** Dragons a wave puts over each standing side's spawn. */
    public static final StateKey<Integer> DRAGONS_PER_TEAM = StateKey.of("creatures.dragons.per-team", Codec.INT, 1);
    /** Over the side's spawn. */
    public static final StateKey<Double> DRAGONS_HEIGHT = StateKey.of("creatures.dragons.height", Codec.DOUBLE, 15.0);
    /** A side may hurt its own dragon (the user, 2026-09-29); its own Bedbug and Dream Defender stay untouchable. */
    public static final StateKey<Boolean> DRAGONS_OWN_HITS = StateKey.of("creatures.dragons.own-hits", Codec.BOOLEAN, true);

    /** Captured swirls: the bedbug's is what its two effects blend to; the golem's shows with no effect behind it. */
    static final int DEFENDER_SWIRL = 5134171;
    public static final String BEDBUG = "Bedbug", DEFENDER = "Dream Defender";

    private static final String TICK = "creatures:tick";
    // captured: full-size invisible stands at -1.38 and 0.94, their text 2.475 over their feet; ours are markers, so the
    // text sits where theirs renders, not the stand
    private static final double STAND_TEXT = 1.975 + 0.5;
    private static final double BEDBUG_TAG = -1.38 + STAND_TEXT, DEFENDER_TAG = 0.94 + STAND_TEXT;
    private static final int BEDBUG_SQUARES = 5, DEFENDER_SQUARES = 10;
    private static final Predicate<Block> EVERY_BLOCK = block -> true;

    private record Creature(MobEntity mob, String team, @Nullable Slot seat, @Nullable String tool, long born, long life,
                            @Nullable Hologram tag, double rise) {
        long left(long tick) { return born + life - tick; }
    }

    private final Map<MobEntity, Creature> creatures = new LinkedHashMap<>();
    private boolean armed;
    private Game game;

    /** A player is its seat's side, a creature its owner's; anyone else is nobody's. */
    private final Sides sides = entity -> {
        if (entity instanceof Player p) {
            String team = Match.teamOf(game, p);
            return team.isEmpty() ? null : team;
        }
        Creature creature = entity instanceof MobEntity mob ? creatures.get(mob) : null;
        return creature != null ? creature.team() : null;
    };

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(BEDBUG_LIFE, BEDBUG_HEALTH, BEDBUG_STRENGTH, BEDBUG_RESISTANCE, DEFENDER_LIFE, DEFENDER_HEALTH,
                DEFENDER_SPEED, DEFENDER_SWING, DRAGONS_LIFT, DRAGONS_OWN_HITS, DRAGONS_PER_TEAM, DRAGONS_HEIGHT);
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        Lives lives = game.module(Lives.class);
        if (lives == null) throw new IllegalStateException("Creatures needs Lives installed before it");
        lives.blame(source -> {
            Creature creature = source instanceof MobEntity mob ? creatures.get(mob) : null;
            if (creature == null || creature.seat() == null || creature.tool() == null) return null;
            Player owner = owner(creature.seat());
            return owner != null ? new Lives.Blame(owner, creature.tool()) : null;
        });
        game.on(ProjectileHitEvent.class, ProjectileHitEvent::shooter, e -> {
            ItemStack item = e.snapshot().item();
            if (!(e.shooter() instanceof Player thrower) || item == null || item.material() != Material.SNOWBALL
                    || game.roster().slotOf(thrower) == null || game.isGhost(thrower)) return;
            bedbug(thrower, Pos.fromPoint(e.hitPoint()));
        });
        game.on(PlayerUseItemOnBlockEvent.class, e -> {
            Player player = e.getPlayer();
            if (e.getHand() != PlayerHand.MAIN || e.getItemStack().material() != Material.IRON_GOLEM_SPAWN_EGG
                    || game.roster().slotOf(player) == null || game.isGhost(player)) return;
            Direction face = e.getBlockFace().toDirection();
            Point at = e.getPosition().add(face.normalX(), face.normalY(), face.normalZ());
            ItemStack held = player.getItemInMainHand();
            player.setItemInMainHand(held.amount() > 1 ? held.withAmount(held.amount() - 1) : ItemStack.AIR);
            defender(player, new Pos(at.blockX() + 0.5, at.blockY(), at.blockZ() + 0.5));
        });
        // its own side never hurts it: not by hand, not by bow; a dragon by the rule
        game.on(PreDamageEvent.class, PreDamageEvent::target, e -> {
            Creature creature = e.target() instanceof MobEntity mob ? creatures.get(mob) : null;
            if (creature == null || e.source() == null) return;
            if (creature.mob() instanceof EnderDragonEntity && game.rules().resolve(Scope.GAME, DRAGONS_OWN_HITS)) return;
            Object side = sides.of(e.source());
            if (side != null && side.equals(creature.team())) e.setCancelled(true);
        });
        game.state().alarms().register(TICK, (tick, payload) -> tick());
    }

    @Override
    public void onEnd(@NotNull Game game, @NotNull Outcome outcome) {
        for (Creature creature : List.copyOf(creatures.values())) dismiss(creature);
    }

    private @Nullable Player owner(Slot seat) {
        String name = game.roster().ownerOf(seat);
        return name != null ? game.memberNamed(name) : null;
    }

    /** A snowball's silverfish at {@code at}, {@code owner}'s for {@link #BEDBUG_LIFE}. */
    public @Nullable SilverfishEntity bedbug(Player owner, Pos at) {
        Slot seat = game.roster().slotOf(owner);
        if (seat == null) return null;
        long life = game.rules().resolve(owner, BEDBUG_LIFE);
        SilverfishEntity fish = Mechanics.mobs.spawn(new SilverfishEntity(Mechanics.mobs), game.shard(), at);
        fish.sides(sides);
        fish.kind(MobKindConfig.builder()
                .maxHealth(game.rules().resolve(owner, BEDBUG_HEALTH))
                .targetChance(1) // captured: a bedbug's first step comes 3 ticks after its spawn
                .targetSight(false) // captured 2026-09-28: bedbugs walk to a wall and face the enemy behind it
                .wanderChance(1) // the user, 2026-09-28: with nobody in reach a bedbug roams, never the silverfish's stands
                .seesInvisible(true) // the user, 2026-09-28: a creature hunts an invisible player as any other
                .hidesInBlocks(false).callsFromBlocks(false)
                .targetSelector(Targets.PLAYERS)
                .attackable(Targets.ANY)
                .build());
        int strength = game.rules().resolve(owner, BEDBUG_STRENGTH);
        int resistance = game.rules().resolve(owner, BEDBUG_RESISTANCE);
        if (strength > 0) fish.addEffect(new Potion(PotionEffect.STRENGTH, strength - 1, (int) life, Potion.PARTICLES_FLAG));
        if (resistance > 0) fish.addEffect(new Potion(PotionEffect.RESISTANCE, resistance - 1, (int) life, Potion.PARTICLES_FLAG));
        name(fish, seat.team(), "Silverfish");
        Hologram tag = Hologram.at(at, Match.scenery(game)).line(BEDBUG_TAG, squares(seat.team(), BEDBUG_SQUARES, BEDBUG_SQUARES));
        creatures.put(fish, new Creature(fish, seat.team(), seat, BEDBUG, game.tick(), life, tag, BEDBUG_TAG));
        owner.sendMessage(ItemLines.of(game).creature(game, life));
        arm();
        return fish;
    }

    /** An egg's golem at {@code at}, {@code owner}'s for {@link #DEFENDER_LIFE}. */
    public @Nullable IronGolemEntity defender(Player owner, Pos at) {
        Slot seat = game.roster().slotOf(owner);
        if (seat == null) return null;
        long life = game.rules().resolve(owner, DEFENDER_LIFE);
        IronGolemEntity golem = new IronGolemEntity(Mechanics.mobs);
        golem.playerCreated(true);
        Mechanics.mobs.spawn(golem, game.shard(), at);
        golem.sides(sides);
        golem.kind(MobKindConfig.builder()
                .maxHealth(game.rules().resolve(owner, DEFENDER_HEALTH))
                .targetChance(1)
                .targetSight(false) // captured 2026-09-28: a defender too
                .speed(game.rules().resolve(owner, DEFENDER_SPEED))
                .attackInterval(game.rules().resolve(owner, DEFENDER_SWING))
                .seesInvisible(true) // the user, 2026-09-28: a creature hunts an invisible player as any other
                .targetSelector(Targets.PLAYERS)
                .attackable(Targets.PLAYERS) // the user, 2026-09-28: never a Bedbug or a dragon, not even back when one hurts it
                .build());
        if (golem.getEntityMeta() instanceof LivingEntityMeta meta) {
            meta.setEffectParticles(List.of(EffectParticles.swirl(DEFENDER_SWIRL, false)));
        }
        // captured: the golem carries no name of its own (nameVisible 0, an empty name); the stand's line is its only tag,
        // and a name at the golem's 3.2 sat on the stand's text at 3.4 (the user, 2026-09-28: the timer unreadable)
        Hologram tag = Hologram.at(at, Match.scenery(game)).line(DEFENDER_TAG, defenderTag(seat.team(), life, golem.getHealth()));
        creatures.put(golem, new Creature(golem, seat.team(), seat, DEFENDER, game.tick(), life, tag, DEFENDER_TAG));
        owner.sendMessage(ItemLines.of(game).creature(game, life));
        arm();
        return golem;
    }

    /** A side's dragon: it eats every block, ignores a blade's enchant, and never touches its own, who may still hit it. */
    public EnderDragonEntity dragon(Team team, Pos at) {
        EnderDragonEntity dragon = EnderDragonEntity.spawn(Mechanics.mobs, game.shard(), at);
        dragon.sides(sides);
        dragon.kind(MobKindConfig.builder()
                .breaks(EVERY_BLOCK)
                .biteOncePerCharge(true) // captured 2026-09-28: six charges reached a player, one bite each
                .pushOncePerCharge(true) // captured (four games): a pass pushes once, on the bite, never every tick under a wing
                .enchantBonus(false)
                .wingLift(game.rules().resolve(Scope.GAME, DRAGONS_LIFT))
                .targetSelector(Targets.PLAYERS)
                .attackable(Targets.ANY)
                .build());
        dragon.roamAround(Center.of(game));
        name(dragon, team.id(), "Dragon");
        creatures.put(dragon, new Creature(dragon, team.id(), null, null, game.tick(), -1, null, 0));
        arm();
        return dragon;
    }

    /** A wave: {@link #DRAGONS_PER_TEAM} over every side still standing, each handed to {@code each}. */
    public void wave(@NotNull BiConsumer<Team, EnderDragonEntity> each) {
        LastStanding standing = game.module(LastStanding.class);
        int perTeam = game.rules().resolve(Scope.GAME, DRAGONS_PER_TEAM);
        double height = game.rules().resolve(Scope.GAME, DRAGONS_HEIGHT);
        for (Team team : game.roster().teams()) {
            if (standing != null && standing.standing(team.id()) == 0) continue;
            Pos spawn = null;
            for (Slot seat : game.roster().seatsOf(team.id())) {
                if (seat.spawn() != null) {
                    spawn = seat.spawn();
                    break;
                }
            }
            if (spawn == null) continue;
            for (int i = 0; i < perTeam; i++) each.accept(team, dragon(team, spawn.add(0, height, 0)));
        }
    }

    /** {@code <Team> <what>} in the side's color, the side's word bold. */
    private void name(MobEntity mob, String team, String what) {
        Team side = game.roster().team(team);
        TextColor color = side.color();
        mob.setCustomName(Component.text(Palette.name(side), color, TextDecoration.BOLD)
                .append(Component.text(" " + what, color).decoration(TextDecoration.BOLD, false)));
        mob.setCustomNameVisible(true);
    }

    private void arm() {
        if (armed) return;
        armed = true;
        game.state().alarms().arm(TICK, game.tick() + 1);
    }

    private void tick() {
        armed = false;
        try {
            for (Creature creature : List.copyOf(creatures.values())) {
                try {
                    step(creature);
                } catch (RuntimeException e) {
                    LOGGER.warn("a creature's tick threw; it is dismissed", e);
                    dismiss(creature);
                }
            }
        } finally {
            if (!creatures.isEmpty()) arm();
        }
    }

    private void step(Creature creature) {
        MobEntity mob = creature.mob();
        if (mob.isRemoved() || mob.isDead()) {
            retire(creature); // captured: the corpse flies and falls its twenty ticks; the tag and the count go now
            return;
        }
        if (creature.life() >= 0 && creature.left(game.tick()) <= 0) {
            dismiss(creature);
            return;
        }
        Hologram tag = creature.tag();
        if (tag == null) return;
        Pos at = mob.getPosition().withView(0, 0).add(0, creature.rise() - 0.5, 0);
        for (Entity stand : tag.stands()) stand.teleport(at);
        long left = creature.left(game.tick());
        tag.text(0, BEDBUG.equals(creature.tool())
                ? squares(creature.team(), lit(mob.getHealth(), (float) mob.getAttributeValue(Attribute.MAX_HEALTH), BEDBUG_SQUARES), BEDBUG_SQUARES)
                : defenderTag(creature.team(), left, mob.getHealth()));
    }

    private void dismiss(Creature creature) {
        retire(creature);
        if (!creature.mob().isRemoved()) creature.mob().remove();
    }

    private void retire(Creature creature) {
        creatures.remove(creature.mob());
        if (creature.tag() != null) creature.tag().remove();
    }

    // the user, 2026-09-29: a bedbug's squares gray from damage, never the clock
    private static int lit(float health, float max, int total) {
        return max <= 0 ? total : Math.min(total, (int) Math.ceil(health * total / max));
    }

    // captured: 240.0s [■■■■■■■■■■], the time in the side's color, a square a level per 2 health, the rest gray
    private Component defenderTag(String team, long left, float health) {
        TextColor color = game.roster().team(team).color();
        int lit = (int) Math.ceil(health / 2.0);
        return Component.text(seconds(left) + "s ", color).append(squares(team, lit, DEFENDER_SQUARES));
    }

    // captured: the leading zero goes: .9s, .0s, -.0s
    public static String seconds(long ticks) {
        String out = String.format(Locale.ROOT, "%.1f", ticks / 20.0);
        if (out.startsWith("0.")) return out.substring(1);
        if (out.startsWith("-0.")) return "-" + out.substring(2);
        return out;
    }

    private Component squares(String team, int lit, int total) {
        TextColor color = game.roster().team(team).color();
        lit = Math.max(0, Math.min(total, lit));
        List<Component> parts = new ArrayList<>();
        parts.add(Component.text("[", NamedTextColor.DARK_GRAY));
        if (lit > 0) parts.add(Component.text("■".repeat(lit), color));
        if (total - lit > 0) parts.add(Component.text("■".repeat(total - lit), NamedTextColor.GRAY));
        parts.add(Component.text("]", NamedTextColor.DARK_GRAY));
        return Component.join(JoinConfiguration.noSeparators(), parts);
    }

    /** The creature a mob is, or null for a mob nobody here sent. */
    public @Nullable String toolOf(MobEntity mob) {
        Creature creature = creatures.get(mob);
        return creature != null ? creature.tool() : null;
    }

    public int count() {
        return creatures.size();
    }
}
