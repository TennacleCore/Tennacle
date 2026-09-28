package tennacle.games.shop;

import net.kyori.adventure.text.format.TextColor;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.PlayerInventory;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A currency held as items of one material in the hotbar and main inventory, spent from the first slot on. */
public record ItemCurrency(@NotNull String id, @NotNull Material material, @NotNull String singular,
                           @NotNull String plural, @NotNull TextColor color) implements Currency {

    @Override
    public int held(@NotNull Player player) {
        PlayerInventory inv = player.getInventory();
        int held = 0;
        for (int i = 0; i < PlayerInventory.INNER_INVENTORY_SIZE; i++) {
            ItemStack in = inv.getItemStack(i);
            if (in.material() == material) held += in.amount();
        }
        return held;
    }

    @Override
    public void take(@NotNull Player player, int amount) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < PlayerInventory.INNER_INVENTORY_SIZE && amount > 0; i++) {
            ItemStack in = inv.getItemStack(i);
            if (in.material() != material) continue;
            int now = Math.min(amount, in.amount());
            inv.setItemStack(i, now == in.amount() ? ItemStack.AIR : in.withAmount(in.amount() - now));
            amount -= now;
        }
    }

    /** The registered currency paid in {@code material}, or null. */
    public static @Nullable ItemCurrency of(@NotNull Material material) {
        for (Currency currency : Currency.all()) {
            if (currency instanceof ItemCurrency item && item.material() == material) return item;
        }
        return null;
    }

    @Override
    public @NotNull String name(int amount) {
        return amount == 1 ? singular : plural;
    }
}
