package tennacle.games.lives;

import tennacle.games.beds.Banners;
import tennacle.games.stats.Haul;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.combat.KillCredit;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.api.event.damage.DamageAppliedEvent;
import io.github.term4.polyp.config.KeySet;
import io.github.term4.polyp.entity.DroppedItemEntity;
import net.kyori.adventure.key.Key;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.codec.Codec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.PlayerInventory;
import net.minestom.server.inventory.TransactionOption;
import net.minestom.server.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a death hands the credited killer: the dead's items {@link #TO_KILLER} admits, a line a kind, the cursor
 * and crafting grid hiding nothing. A killer still waiting on a respawn is paid at it, since its kit would wipe
 * the lot. A death {@link #DROPS_FROM} names, or a final walk-out under {@link #LEAVE_DROPS}, lays the same items
 * where the dead stood instead, for anyone. What else falls where they died is Polyp's {@code death/spill}.
 */
public final class Spoils implements GameModule {

    /** Item keys, in the order their lines print; {@code only()}, nothing, by default. */
    public static final StateKey<KeySet> TO_KILLER = StateKey.of("combat.death.to-killer", KeySet.CODEC, KeySet.only());
    /** What a death by a member's creature hands the creature's owner; nothing, as Hypixel (the user, 2026-09-28). */
    public static final StateKey<KeySet> TO_OWNER = StateKey.of("combat.death.to-owner", KeySet.CODEC, KeySet.only());
    /** Entity types whose blow drops the spoils instead: the killing one, or the last landed before a fall or the void. */
    public static final StateKey<KeySet> DROPS_FROM = StateKey.of("combat.death.drops-from", KeySet.CODEC, KeySet.only());
    /** A final walk-out drops the spoils rather than paying the credited killer. */
    public static final StateKey<Boolean> LEAVE_DROPS = StateKey.of("combat.death.leave-drops", Codec.BOOLEAN, false);

    private record Blow(Key type, long tick) {}

    private final Map<Integer, List<ItemStack>> owed = new ConcurrentHashMap<>();
    private final Map<UUID, Blow> lastBlow = new ConcurrentHashMap<>();
    private Game game;
    private Respawns respawns;

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(TO_KILLER, TO_OWNER, DROPS_FROM, LEAVE_DROPS);
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        respawns = game.module(Respawns.class);
        Lives lives = game.module(Lives.class);
        if (respawns == null || lives == null) throw new IllegalStateException("Spoils needs Respawns and Lives installed before it");
        lives.onDeath(this::died);
        respawns.onRespawn(this::pay); // after Lives' own, which refits the kit
        game.on(DamageAppliedEvent.class, DamageAppliedEvent::target, e -> {
            if (!e.outcome().landed() || e.fatalCancelled() || !(e.target() instanceof Player victim)) return;
            Entity source = e.snapshot().source();
            if (source == null || source == victim || game.roster().slotOf(victim) == null) return;
            lastBlow.put(victim.getUuid(), new Blow(source.getEntityType().key(), game.state().tick()));
        });
    }

    private void died(Lives.Death death) {
        Blow blow = lastBlow.remove(death.victim().getUuid());
        Scope scope = Scope.slot(death.seat().index());
        KeySet taken = game.rules().resolve(scope, death.tool() != null ? TO_OWNER : TO_KILLER);
        if (drops(death, blow, scope)) {
            for (List<ItemStack> stacks : take(death.victim(), taken).values()) {
                for (ItemStack stack : stacks) DroppedItemEntity.spawn(game.shard(), death.where(), Vec.ZERO, stack, null, 0);
            }
            return;
        }
        Player killer = death.killer();
        Slot seat = killer != null && killer != death.victim() ? game.roster().slotOf(killer) : null;
        if (seat == null) return;
        Map<Key, List<ItemStack>> kinds = take(death.victim(), taken);
        if (kinds.isEmpty()) return;
        boolean waiting = game.isGhost(killer) || respawns.isDead(killer);
        List<ItemStack> due = waiting ? owed.computeIfAbsent(seat.index(), index -> new ArrayList<>()) : null;
        for (List<ItemStack> stacks : kinds.values()) {
            int amount = 0;
            for (ItemStack stack : stacks) {
                amount += stack.amount();
                Haul.handed(game, death.victim(), killer, stack);
            }
            if (due != null) due.addAll(stacks);
            else give(killer, stacks);
            killer.sendMessage(Banners.spoil(stacks.getFirst(), amount));
        }
    }

    private boolean drops(Lives.Death death, @Nullable Blow blow, Scope scope) {
        if (death.left()) return death.last() && game.rules().resolve(scope, LEAVE_DROPS);
        Key type = death.source() != null ? death.source().getEntityType().key()
                : blow != null && game.state().tick() - blow.tick() <= game.rules().resolve(scope, KillCredit.TICKS) ? blow.type()
                : null;
        return type != null && game.rules().resolve(scope, DROPS_FROM).admits(type);
    }

    // out of every slot and the cursor, grouped by kind: a listed selection's order first, then as found
    private static Map<Key, List<ItemStack>> take(Player victim, KeySet taken) {
        Map<Key, List<ItemStack>> kinds = new LinkedHashMap<>();
        if (taken instanceof KeySet.Listed listed && !listed.except()) listed.keys().forEach(key -> kinds.put(key, new ArrayList<>()));
        PlayerInventory inv = victim.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack stack = inv.getItemStack(slot);
            if (stack.isAir() || !taken.admits(stack.material().key())) continue;
            inv.setItemStack(slot, ItemStack.AIR);
            kinds.computeIfAbsent(stack.material().key(), key -> new ArrayList<>()).add(stack);
        }
        ItemStack cursor = inv.getCursorItem();
        if (!cursor.isAir() && taken.admits(cursor.material().key())) {
            inv.setCursorItem(ItemStack.AIR);
            kinds.computeIfAbsent(cursor.material().key(), key -> new ArrayList<>()).add(cursor);
        }
        kinds.values().removeIf(List::isEmpty);
        return kinds;
    }

    private void pay(Player player, Slot seat) {
        List<ItemStack> due = owed.remove(seat.index());
        if (due != null) give(player, due);
    }

    // a full inventory: the rest at their feet
    private void give(Player player, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            ItemStack left = player.getInventory().addItemStack(stack, TransactionOption.ALL);
            if (!left.isAir()) DroppedItemEntity.spawn(game.shard(), player.getPosition(), Vec.ZERO, left, null, 0);
        }
    }
}
