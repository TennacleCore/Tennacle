package tennacle.games.bedwars;

import tennacle.games.bedwars.Wares.Tab;
import tennacle.games.bedwars.Wares.Ware;

import io.github.term4.canvas.screen.Items;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.grotto.Game;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Where a shift-clicked ware goes in Quick Buy (captured 2026-09-27): the ware on top, the player's pins below;
 * a click sets the slot with a pling and no line, then back to Quick Buy.
 */
final class QuickBuyPicker {

    private QuickBuyPicker() {}

    static void open(@Nullable Game game, Player viewer, Ware ware) {
        Screen.Builder picker = Screen.rows(6).title(Component.text("Adding to Quick Buy..."));
        picker.item(4, v -> adding(game, v, ware));
        for (int i = 0; i < ItemShop.GRID.length; i++) {
            int index = i;
            picker.button(ItemShop.GRID[i], v -> pinned(game, v, index), (v, click) -> {
                ItemShop.pin(v, index, ware.offer().id());
                v.playSound(Lines.BOUGHT);
                ItemShop.show(game, v, Tab.QUICK_BUY);
            });
        }
        if (game == null) {
            picker.button(49, Items.lore(Items.named(Material.ARROW, Component.text("Go Back", NamedTextColor.GREEN)), List.of()),
                    (v, click) -> ItemShop.show(null, v, ware.tab()));
            Screens.swap(viewer, picker.build());
        } else {
            Screens.open(viewer, picker.build());
        }
    }

    private static ItemStack adding(@Nullable Game game, Player viewer, Ware ware) {
        List<Component> lore = ItemShop.header(game, viewer, ware);
        lore.add(Component.text("Adding item to Quick Slot!", NamedTextColor.YELLOW));
        return ItemShop.face(game, viewer, ware, lore);
    }

    private static ItemStack pinned(@Nullable Game game, Player viewer, int index) {
        Ware there = Wares.of(ItemShop.pins(viewer).get(index));
        if (there == null) {
            return Items.lore(Items.named(Material.RED_STAINED_GLASS_PANE,
                    Component.text("Empty slot!", NamedTextColor.RED)), List.of(Component.text("Click to set!", NamedTextColor.YELLOW)));
        }
        return ItemShop.face(game, viewer, there, List.of(Component.text("Click to replace!", NamedTextColor.YELLOW)));
    }
}
