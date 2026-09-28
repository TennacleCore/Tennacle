package tennacle.games.bedwars;

import tennacle.games.shop.Goods;
import tennacle.games.shop.Offer;
import tennacle.games.shop.Price;
import tennacle.games.shop.Refusal;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.kit.Kit;
import io.github.term4.grotto.modules.gear.Kits;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.StateKey;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.PlayerInventory;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * A sword bought (our play): it takes the place of the hotbar's worse sword, the held one first. The wooden sword
 * it replaces is gone; a bought one moves out of the hotbar. With no sword in the hotbar it goes in as melee.
 */
final class Sword implements Offer {

    private static final List<Material> LADDER = List.of(Material.WOODEN_SWORD, Material.STONE_SWORD,
            Material.IRON_SWORD, Material.DIAMOND_SWORD);

    private final String id;
    private final Material material;
    private final StateKey<Price> price;

    Sword(String id, Material material, Price price) {
        this.id = id;
        this.material = material;
        this.price = Goods.rule(id, price);
    }

    /** Any sword above the wooden one: what the kit's wooden sword yields to. */
    static boolean bought(@NotNull ItemStack item) {
        return rank(item) > 0;
    }

    StateKey<Price> rule() {
        return price;
    }

    ItemStack stack() {
        return ItemStack.of(material);
    }

    @Override
    public @NotNull String id() {
        return id;
    }

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(price);
    }

    @Override
    public @NotNull Price price(@NotNull Game game, @NotNull Player buyer) {
        return game.rules().resolve(buyer, price);
    }

    @Override
    public @Nullable Refusal refusal(@NotNull Game game, @NotNull Player buyer) {
        PlayerInventory inv = buyer.getInventory();
        int replaced = replaced(buyer);
        if (replaced < 0) return kits(game).fits(buyer, stack()) ? null : Refusal.FULL;
        boolean wooden = rank(inv.getItemStack(replaced)) == 0;
        return wooden || Kit.firstOutOfHotbar(inv, replaced) >= 0 ? null : Refusal.FULL;
    }

    @Override
    public void grant(@NotNull Game game, @NotNull Player buyer) {
        Kits kits = kits(game);
        Slot seat = game.roster().slotOf(buyer);
        int replaced = replaced(buyer);
        if (seat == null) return;
        if (replaced < 0) {
            if (best(buyer.getInventory()) > rank(stack())) kits.giveAt(buyer, -1, stack()); // a worse sword leaves yours be
            else kits.give(buyer, "melee", stack());
            return;
        }
        PlayerInventory inv = buyer.getInventory();
        ItemStack old = inv.getItemStack(replaced);
        if (rank(old) > 0) inv.setItemStack(Kit.firstOutOfHotbar(inv, replaced), old);
        inv.setItemStack(replaced, kits.selected(buyer).stamped(game, seat, stack()));
        kits.settle(buyer);
    }

    // the hotbar sword this one takes the place of: the held slot's when it is worse, else the best worse one; -1 = none
    private int replaced(Player buyer) {
        PlayerInventory inv = buyer.getInventory();
        int rank = rank(stack());
        int held = buyer.getHeldSlot();
        int heldRank = rank(inv.getItemStack(held));
        if (heldRank >= 0 && heldRank < rank) return held;
        int best = -1;
        for (int i = 0; i < 9; i++) {
            int at = rank(inv.getItemStack(i));
            if (at >= 0 && at < rank && (best < 0 || at > rank(inv.getItemStack(best)))) best = i;
        }
        return best;
    }

    private static int best(PlayerInventory inv) {
        int best = -1;
        for (int i = 0; i < PlayerInventory.INNER_INVENTORY_SIZE; i++) best = Math.max(best, rank(inv.getItemStack(i)));
        return best;
    }

    private static int rank(ItemStack item) {
        return LADDER.indexOf(item.material());
    }

    private static Kits kits(Game game) {
        return game.module(Kits.class);
    }
}
