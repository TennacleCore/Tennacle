package tennacle.games.bedwars;

import tennacle.games.shop.Goods;
import tennacle.games.shop.Offer;
import tennacle.games.shop.Price;
import tennacle.games.shop.Refusal;

import io.github.term4.grotto.Game;
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
 * A sword bought (Hypixel since 2023-05): it takes the place of a worse sword, the held slot's first, else of the
 * worst one held, and goes in as melee when none is worse. A worse sword than yours can still be bought.
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
        return worse(buyer) >= 0 || kits(game).fits(buyer, stack()) ? null : Refusal.FULL;
    }

    @Override
    public void grant(@NotNull Game game, @NotNull Player buyer) {
        int slot = worse(buyer);
        Slot seat = game.roster().slotOf(buyer);
        if (slot < 0 || seat == null) {
            kits(game).give(buyer, "melee", stack());
            return;
        }
        buyer.getInventory().setItemStack(slot, kits(game).selected(buyer).stamped(game, seat, stack()));
    }

    // -1 = no worse sword held
    private int worse(Player buyer) {
        PlayerInventory inv = buyer.getInventory();
        int rank = LADDER.indexOf(material);
        int held = buyer.getHeldSlot();
        if (below(inv.getItemStack(held), rank)) return held;
        int worst = -1;
        for (int i = 0; i < PlayerInventory.INNER_INVENTORY_SIZE; i++) {
            ItemStack in = inv.getItemStack(i);
            if (below(in, rank) && (worst < 0 || LADDER.indexOf(in.material()) < LADDER.indexOf(inv.getItemStack(worst).material()))) {
                worst = i;
            }
        }
        return worst;
    }

    private static boolean below(ItemStack item, int rank) {
        int at = LADDER.indexOf(item.material());
        return at >= 0 && at < rank;
    }

    private static Kits kits(Game game) {
        return game.module(Kits.class);
    }
}
