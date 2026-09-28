package tennacle.games.bedwars;

import tennacle.games.KitLayouts;
import tennacle.games.bedwars.Wares.Tab;

import io.github.term4.canvas.screen.Items;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.grotto.Game;
import io.github.term4.polyp.platform.inventory.InventorySync;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Hotbar Manager (captured 2026-09-24 and 27): the categories to pick up over the hotbar they go in. A
 * category taken onto the cursor lands in the hotbar slot clicked, and a filled slot clicked empties. It edits
 * the player's Bed Wars kit layout, which every spawn and every purchase reads.
 */
final class HotbarManager {

    record Category(String role, String name, Material icon) {}

    static final List<Category> CATEGORIES = List.of(
            new Category("blocks", "Blocks", Material.TERRACOTTA),
            new Category("melee", "Melee", Material.GOLDEN_SWORD),
            new Category("pickaxe", "Pickaxe", Material.IRON_PICKAXE),
            new Category("axe", "Axe", Material.IRON_AXE),
            new Category("shears", "Shears", Material.SHEARS),
            new Category("ranged", "Ranged", Material.BOW),
            new Category("potions", "Potions", Material.BREWING_STAND),
            new Category("utility", "Utility", Material.TNT));

    private static final Tag<String> HELD = Tag.String("bedwars:hotbar-category");

    private HotbarManager() {}

    static void open(Game game, Player viewer) {
        // the viewer's own inventory is theirs to arrange, but a held category must never land in it
        Screen.Builder manager = Screen.rows(6).title(Component.text("Hotbar Manager"))
                .ownClicks((v, click) -> v.getInventory().getCursorItem().getTag(HELD) == null);
        for (int i = 0; i < CATEGORIES.size(); i++) {
            Category category = CATEGORIES.get(i);
            manager.button(10 + i, pickable(category), (v, click) -> v.getInventory().setCursorItem(held(category)));
        }
        ItemStack pane = Items.lore(Items.named(Material.GRAY_STAINED_GLASS_PANE, Component.text("⬆ ", NamedTextColor.DARK_GRAY)
                        .append(Component.text("Categories", NamedTextColor.GRAY))),
                List.of(Component.text("⬇ ", NamedTextColor.DARK_GRAY).append(Component.text("Hotbar", NamedTextColor.GRAY))));
        for (int i = 18; i < 27; i++) manager.item(i, pane);
        for (int slot = 0; slot < 9; slot++) {
            int hotbar = slot;
            manager.button(27 + slot, v -> placed(game, v, hotbar), (v, click) -> place(game, v, hotbar));
        }
        manager.button(48, Items.lore(Items.named(Material.ARROW, Component.text("Go Back", NamedTextColor.GREEN)),
                List.of(ItemShop.gray("To Quick Buy"))), (v, click) -> {
            v.getInventory().setCursorItem(ItemStack.AIR); // a swapped window drops the cursor
            ItemShop.open(game, v, Tab.QUICK_BUY);
        });
        manager.button(50, Items.lore(Items.named(Material.BARRIER, Component.text("Reset to Default", NamedTextColor.RED)),
                List.of(ItemShop.gray("Reset your hotbar to the default."))), (v, click) -> {
            v.getInventory().setCursorItem(ItemStack.AIR);
            KitLayouts.set(v, BedWars.KIT.id(), Map.of());
            Screens.repaint(v);
        });
        Screens.open(viewer, manager.build());
    }

    private static ItemStack pickable(Category category) {
        return Items.bare(Items.lore(Items.named(category.icon(), Component.text(category.name(), NamedTextColor.GREEN)),
                List.of(ItemShop.gray("Drag this to a hotbar slot below to"), ItemShop.gray("favor that slot when purchasing an"),
                        ItemShop.gray("item in this category or on spawn."), Component.empty(),
                        Component.text("Click to drag!", NamedTextColor.YELLOW))));
    }

    private static ItemStack held(Category category) {
        return pickable(category).withTag(HELD, category.role()).withTag(InventorySync.CURSOR_TOKEN, true);
    }

    private static ItemStack placed(Game game, Player viewer, int hotbar) {
        Category category = in(game, viewer, hotbar);
        if (category == null) return ItemStack.AIR;
        return Items.bare(Items.lore(Items.named(category.icon(), Component.text(category.name(), NamedTextColor.GREEN)),
                List.of(ItemShop.gray(category.name() + " items will prioritize this slot!"), Component.empty(),
                        Component.text("Click to remove!", NamedTextColor.YELLOW))));
    }

    private static void place(Game game, Player viewer, int hotbar) {
        Map<String, Integer> mine = KitLayouts.of(viewer, BedWars.KIT.id());
        Map<String, Integer> layout = new HashMap<>(mine != null ? mine : Map.of());
        String held = viewer.getInventory().getCursorItem().getTag(HELD);
        for (Category category : CATEGORIES) {
            if (!category.role().equals(held) && BedWars.KIT.slotFor(game, viewer, category.role()) == hotbar) {
                layout.put(category.role(), -1);
            }
        }
        if (held != null) {
            layout.put(held, hotbar);
            viewer.getInventory().setCursorItem(ItemStack.AIR);
        }
        KitLayouts.set(viewer, BedWars.KIT.id(), layout);
        Screens.repaint(viewer);
    }

    private static @Nullable Category in(Game game, Player viewer, int hotbar) {
        for (Category category : CATEGORIES) {
            if (BedWars.KIT.slotFor(game, viewer, category.role()) == hotbar) return category;
        }
        return null;
    }
}
