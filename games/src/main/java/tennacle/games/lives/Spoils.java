package tennacle.games.lives;

import tennacle.games.beds.Banners;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.config.KeySet;
import io.github.term4.polyp.entity.DroppedItemEntity;
import net.kyori.adventure.key.Key;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.PlayerInventory;
import net.minestom.server.inventory.TransactionOption;
import net.minestom.server.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a death hands the credited killer: the dead's items {@link #TO_KILLER} admits, a line a kind, the cursor
 * and crafting grid hiding nothing. A killer still waiting on a respawn is paid at it, since its kit would wipe
 * the lot. What falls where they died is Polyp's {@code death/spill}.
 */
public final class Spoils implements GameModule {

    /** Item keys, in the order their lines print; {@code only()}, nothing, by default. */
    public static final StateKey<KeySet> TO_KILLER = StateKey.of("combat.death.to-killer", KeySet.CODEC, KeySet.only());
    /** What a death by a member's creature hands the creature's owner; nothing, as Hypixel (the user, 2026-09-28). */
    public static final StateKey<KeySet> TO_OWNER = StateKey.of("combat.death.to-owner", KeySet.CODEC, KeySet.only());

    private final Map<Integer, List<ItemStack>> owed = new ConcurrentHashMap<>();
    private Game game;
    private Respawns respawns;

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(TO_KILLER, TO_OWNER);
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        respawns = game.module(Respawns.class);
        Lives lives = game.module(Lives.class);
        if (respawns == null || lives == null) throw new IllegalStateException("Spoils needs Respawns and Lives installed before it");
        lives.onDeath(this::died);
        respawns.onRespawn(this::pay); // after Lives' own, which refits the kit
    }

    private void died(Lives.Death death) {
        Player killer = death.killer();
        Slot seat = killer != null && killer != death.victim() ? game.roster().slotOf(killer) : null;
        if (seat == null) return;
        KeySet taken = game.rules().resolve(Scope.slot(death.seat().index()), death.tool() != null ? TO_OWNER : TO_KILLER);
        Map<Key, List<ItemStack>> kinds = take(death.victim(), taken);
        if (kinds.isEmpty()) return;
        boolean waiting = game.isGhost(killer) || respawns.isDead(killer);
        List<ItemStack> due = waiting ? owed.computeIfAbsent(seat.index(), index -> new ArrayList<>()) : null;
        for (List<ItemStack> stacks : kinds.values()) {
            int amount = 0;
            for (ItemStack stack : stacks) amount += stack.amount();
            if (due != null) due.addAll(stacks);
            else give(killer, stacks);
            killer.sendMessage(Banners.spoil(stacks.getFirst(), amount));
        }
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
