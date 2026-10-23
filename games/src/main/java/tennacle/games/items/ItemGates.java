package tennacle.games.items;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.api.event.explosion.ExplosionEvent;
import io.github.term4.polyp.mechanics.projectile.entities.FireballEntity;
import io.github.term4.polyp.api.event.consume.ConsumeEvent;
import io.github.term4.polyp.api.event.projectile.ProjectileLaunchEvent;
import net.minestom.server.codec.Codec;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.games.Match;
import tennacle.games.Timeline;
import tennacle.games.beds.Beds;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A season's item rules: each item's {@link Unlock} as the rule {@code items.<id>}. A locked item can be bought and
 * held but not used. A drink or a meal is judged as it finishes, so one started before the unlock and finished after
 * goes through; a throw as it leaves; a placement (blocks, TNT, buckets) by {@code Building} as it lands. A disabled
 * item is not sold at all. {@code drops.fireball-proof} names the dropped items a fireball blast leaves whole.
 */
public final class ItemGates implements GameModule {

    /** What a mode says when it refuses a use: {@code when} reads "after a bed breaks", null for a disabled item. */
    @FunctionalInterface
    public interface Copy {
        void refused(@NotNull Game game, @NotNull Player player, @NotNull ItemStack item, @Nullable String when);
    }

    // one line a press: a 1.8 right click sends two packets
    private static final long QUIET_TICKS = 4;

    private static final Codec<Unlock> CODEC = Codec.STRING.transform(Unlock::parse, Unlock::toString);

    public static final StateKey<FireballProof> FIREBALL_PROOF = StateKey.of("drops.fireball-proof",
            Codec.STRING.transform(FireballProof::parse, FireballProof::toString), FireballProof.NONE);

    private final Map<String, StateKey<Unlock>> keys = new LinkedHashMap<>();
    private final Function<ItemStack, @Nullable String> idOf;
    private final Copy copy;
    private final Map<UUID, Long> told = new ConcurrentHashMap<>();
    private Game game;
    private int broken;

    private ItemGates(Collection<String> ids, Function<ItemStack, @Nullable String> idOf, Copy copy) {
        for (String id : ids) keys.put(id, StateKey.of("items." + id, CODEC, Unlock.ALLOWED));
        this.idOf = idOf;
        this.copy = copy;
    }

    /** Gates {@code ids}; {@code idOf} says which of them a held item is, or null for none. */
    public static @NotNull ItemGates of(@NotNull Collection<String> ids, @NotNull Function<ItemStack, @Nullable String> idOf,
                                        @NotNull Copy copy) {
        return new ItemGates(ids, idOf, copy);
    }

    public static @Nullable ItemGates of(@NotNull Game game) {
        return game.module(ItemGates.class);
    }

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        Set<StateKey<?>> rules = new java.util.LinkedHashSet<>(keys.values());
        rules.add(FIREBALL_PROOF);
        return rules;
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        Beds beds = game.module(Beds.class);
        if (beds != null) beds.onFall(team -> broken++);
        game.on(ConsumeEvent.class, ConsumeEvent::user, e -> {
            if (refuse(e.user(), e.item())) e.setCancelled(true);
        });
        game.on(ProjectileLaunchEvent.class, ProjectileLaunchEvent::shooter, e -> {
            ItemStack item = e.snapshot().item();
            if (e.shooter() instanceof Player thrower && item != null && refuse(thrower, item)) e.setCancelled(true);
        });
        game.onWorld(ExplosionEvent.class, ExplosionEvent::world, e -> {
            if (!(e.source() instanceof FireballEntity fireball)) return;
            FireballProof proof = game.rules().resolve(Scope.GAME, FIREBALL_PROOF);
            if (proof.materials().isEmpty()) return;
            Player thrower = fireball.getShooter() instanceof Player p ? p : null;
            for (Unlock.Condition end : proof.until()) if (met(thrower, end)) return;
            for (ExplosionEvent.Target target : e.targets()) {
                if (target.entity() instanceof ItemEntity drop && proof.materials().contains(drop.getItemStack().material())) {
                    target.setDamage(0f);
                }
            }
        });
    }

    /** {@code id}'s rule for {@code player}, or allowed for an item this module doesn't know. */
    public @NotNull Unlock unlock(@NotNull Player player, @NotNull String id) {
        StateKey<Unlock> key = keys.get(id);
        return key != null ? game.rules().resolve(player, key) : Unlock.ALLOWED;
    }

    /** Whether {@code id} is off for {@code player}: not sold, not used. */
    public boolean disabled(@NotNull Player player, @NotNull String id) {
        return unlock(player, id).disabled();
    }

    /** Whether {@code player} may use {@code item} now. */
    public boolean usable(@NotNull Player player, @NotNull ItemStack item) {
        String id = idOf.apply(item);
        return id == null || usable(player, unlock(player, id));
    }

    /** Refuses {@code item}'s use with the mode's line when it is locked; whether it did. */
    public boolean refuse(@NotNull Player player, @NotNull ItemStack item) {
        String id = idOf.apply(item);
        if (id == null) return false;
        Unlock unlock = unlock(player, id);
        if (usable(player, unlock)) return false;
        long now = game.tick();
        Long last = told.get(player.getUuid());
        if (last == null || now - last > QUIET_TICKS) {
            told.put(player.getUuid(), now);
            copy.refused(game, player, item, unlock.describe(this::label));
        }
        return true;
    }

    /** {@code id}'s unlock as a player reads it ("after a bed breaks"), null when it is open or disabled. */
    public @Nullable String when(@NotNull Player player, @NotNull String id) {
        return unlock(player, id).describe(this::label);
    }

    private boolean usable(Player player, Unlock unlock) {
        if (unlock.disabled()) return false;
        if (unlock.anyOf().isEmpty()) return true;
        for (Unlock.Condition condition : unlock.anyOf()) if (met(player, condition)) return true;
        return false;
    }

    private boolean met(@Nullable Player player, Unlock.Condition condition) {
        return switch (condition) {
            case Unlock.Condition.Beds beds -> beds.count() == Unlock.Condition.Beds.ALL ? Beds.standing(game).isEmpty()
                    : broken >= beds.count();
            case Unlock.Condition.OwnBed ignored -> {
                String team = player != null ? Match.teamOf(game, player) : null;
                yield team != null && !Beds.stands(game, team);
            }
            case Unlock.Condition.Event event -> {
                Timeline timeline = game.module(Timeline.class);
                yield timeline != null && timeline.fired(event.id());
            }
        };
    }

    private String label(String event) {
        Timeline timeline = game.module(Timeline.class);
        String label = timeline != null ? timeline.label(event) : null;
        return label != null ? label : event;
    }
}
