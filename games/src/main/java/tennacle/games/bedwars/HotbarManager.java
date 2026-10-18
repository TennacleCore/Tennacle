package tennacle.games.bedwars;

import tennacle.Confirm;
import tennacle.games.bedwars.HotbarOrders.Layouts;
import tennacle.games.bedwars.Wares.Tab;
import tennacle.games.bedwars.Wares.Ware;

import io.github.term4.canvas.screen.Items;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.grotto.Game;
import io.github.term4.polyp.platform.inventory.InventorySync;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The Hotbar Manager: picks in order for each hotbar slot, a kind or one shop item, the first one held going there.
 * Hypixel's window shape (captured 2026-09-24 and 27) with our ordered slots, single items picked from the shop's
 * tabs, and an optional after-bed layout.
 */
final class HotbarManager {

    record Category(String role, String name, Material icon, @Nullable Tab tab) {}

    // Utility wears a crafting table, not Hypixel's TNT, so it never reads as the TNT pick
    static final List<Category> CATEGORIES = List.of(
            new Category("blocks", "Blocks", Material.TERRACOTTA, Tab.BLOCKS),
            new Category("melee", "Melee", Material.GOLDEN_SWORD, Tab.MELEE),
            new Category("pickaxe", "Pickaxe", Material.IRON_PICKAXE, null),
            new Category("axe", "Axe", Material.IRON_AXE, null),
            new Category("shears", "Shears", Material.SHEARS, null),
            new Category("ranged", "Ranged", Material.BOW, Tab.RANGED),
            new Category("potions", "Potions", Material.BREWING_STAND, Tab.POTIONS),
            new Category("utility", "Utility", Material.CRAFTING_TABLE, Tab.UTILITY));

    private static final Tag<String> HELD = Tag.String("bedwars:hotbar-pick");
    private static final Tag<Boolean> AFTER = Tag.Transient("bedwars:hotbar-after");

    private HotbarManager() {}

    static void open(Game game, Player viewer) {
        open(game, viewer, false);
    }

    /** From the lobby's Kits tab: no game around it, Go Back returning to the tab. */
    static void lobby(Player viewer) {
        open(null, viewer, true);
    }

    private static void open(@Nullable Game game, Player viewer, boolean pushed) {
        viewer.removeTag(AFTER);
        Screen.Builder manager = window("Hotbar Manager");
        manager.button(3, v -> tab(v, false), (v, click) -> {
            drop(v);
            v.removeTag(AFTER);
            Screens.repaint(v);
        });
        manager.button(5, v -> tab(v, true), (v, click) -> {
            drop(v);
            Layouts mine = HotbarOrders.of(v);
            if (click instanceof Click.Right) {
                HotbarOrders.set(v, new Layouts(mine.main(), List.of()));
                v.removeTag(AFTER);
            } else {
                if (!mine.splits()) HotbarOrders.set(v, new Layouts(mine.main(), copy(mine.main())));
                v.setTag(AFTER, true);
            }
            Screens.repaint(v);
        });
        categories(game, manager);
        manager.row(2, divider("Picks", "Hotbar"));
        for (int slot = 0; slot < HotbarOrders.SLOTS; slot++) {
            int hotbar = slot;
            manager.button(27 + slot, v -> slotItem(game, v, hotbar), (v, click) -> {
                String pick = v.getInventory().getCursorItem().getTag(HELD);
                if (pick != null) {
                    v.getInventory().setCursorItem(ItemStack.AIR);
                    edit(v, hotbar, picks -> {
                        List<String> out = new ArrayList<>(List.of(pick));
                        picks.stream().filter(p -> !p.equals(pick)).forEach(out::add);
                        return out.subList(0, Math.min(out.size(), HotbarOrders.PICKS));
                    });
                } else if (click instanceof Click.Right) {
                    edit(v, hotbar, picks -> picks.isEmpty() ? picks : picks.subList(1, picks.size()));
                } else {
                    order(game, v, hotbar);
                }
            });
        }
        manager.button(48, goBack(game != null ? "To Quick Buy" : "To Kits"), (v, click) -> {
            drop(v);
            if (game != null) ItemShop.open(game, v, Tab.QUICK_BUY);
            else Screens.back(v);
        });
        manager.button(50, Items.lore(Items.named(Material.BARRIER, Component.text("Reset to Default", NamedTextColor.RED)),
                List.of(Items.gray("Reset your hotbar to the default."), Component.empty(),
                        Component.text("Click twice to reset!", NamedTextColor.YELLOW))), (v, click) -> {
            drop(v);
            if (!Confirm.again(v, "bedwars hotbar reset")) {
                v.sendMessage(Component.text("Click again to reset your hotbar to the default.", NamedTextColor.YELLOW));
                return;
            }
            HotbarOrders.set(v, new Layouts(HotbarOrders.defaults(), List.of()));
            v.removeTag(AFTER);
            Screens.repaint(v);
        });
        if (pushed) Screens.push(viewer, manager.build());
        else Screens.open(viewer, manager.build());
    }

    // one slot's order in a row, first on the left, arranged as a kit editor's slots are
    private static void order(@Nullable Game game, Player viewer, int hotbar) {
        Screen.Builder screen = window("Hotbar Slot " + (hotbar + 1));
        categories(game, screen);
        screen.row(2, divider("Picks", "Order"));
        for (int i = 0; i < HotbarOrders.PICKS; i++) {
            int at = i;
            screen.button(27 + i, v -> orderItem(game, v, hotbar, at), (v, click) -> place(game, v, hotbar, at, click));
        }
        screen.button(48, goBack("To Hotbar Manager"), (v, click) -> {
            drop(v);
            Screens.back(v);
        });
        screen.button(50, Items.lore(Items.named(Material.BARRIER, Component.text("Clear", NamedTextColor.RED)),
                List.of(Items.gray("Empties this slot's order."), Component.empty(),
                        Component.text("Click to clear!", NamedTextColor.YELLOW))), (v, click) -> {
            drop(v);
            edit(v, hotbar, picks -> List.of());
        });
        Screens.push(viewer, screen.build());
    }

    // a held pick swaps with the one there or joins the end; an empty cursor picks one up, a right click removes it
    private static void place(@Nullable Game game, Player viewer, int hotbar, int at, Click click) {
        String held = viewer.getInventory().getCursorItem().getTag(HELD);
        List<String> picks = editing(viewer).get(hotbar);
        String there = at < picks.size() ? picks.get(at) : null;
        if (held == null) {
            if (there == null) return;
            edit(viewer, hotbar, order -> {
                List<String> out = new ArrayList<>(order);
                out.remove(at);
                return out;
            });
            if (!(click instanceof Click.Right)) viewer.getInventory().setCursorItem(held(there, shown(game, viewer, there)));
            return;
        }
        boolean swaps = there != null && !there.equals(held);
        edit(viewer, hotbar, order -> {
            List<String> out = new ArrayList<>(order);
            if (swaps) {
                out.remove(held);
                out.set(out.indexOf(there), held);
            } else if (!out.contains(held)) {
                out.add(held);
            }
            return out;
        });
        viewer.getInventory().setCursorItem(swaps ? held(there, shown(game, viewer, there)) : ItemStack.AIR);
    }

    // the shop's tabs for single items; the one chosen goes onto the cursor in the screen underneath
    private static void items(@Nullable Game game, Player viewer, Category open, boolean swap) {
        Screen.Builder page = window(open.tab().title);
        List<Category> tabbed = CATEGORIES.stream().filter(c -> c.tab() != null).toList();
        int first = (9 - tabbed.size()) / 2;
        for (int i = 0; i < tabbed.size(); i++) {
            Category category = tabbed.get(i);
            boolean lit = category == open;
            ItemStack icon = Items.named(category.tab().icon, Component.text(category.tab().title, NamedTextColor.GREEN));
            if (!lit) icon = Items.lore(icon, List.of(Component.text("Click to view!", NamedTextColor.YELLOW)));
            page.button(first + i, Items.bare(icon), (v, click) -> {
                if (!lit) items(game, v, category, true);
            });
            page.item(9 + first + i, ItemShop.pane(lit));
        }
        List<Ware> wares = Wares.pickable().stream().filter(w -> w.tab() == open.tab()).toList();
        for (int i = 0; i < Math.min(wares.size(), ItemShop.GRID.length); i++) {
            Ware ware = wares.get(i);
            String pick = HotbarOrders.WARE + ware.offer().id();
            page.button(ItemShop.GRID[i], v -> Items.bare(Items.lore(Items.named(Wares.icon(ware, game, v), Component.text(ware.name(game, v), NamedTextColor.GREEN)),
                    List.of(Items.gray("Only this item, not the whole"), Items.gray(open.name() + " category."), Component.empty(),
                            Component.text("Click to pick up!", NamedTextColor.YELLOW)))), (v, click) -> {
                Screens.back(v);
                v.getInventory().setCursorItem(held(pick, shown(game, v, pick)));
            });
        }
        page.button(49, goBack("To the hotbar"), (v, click) -> Screens.back(v));
        if (swap) Screens.swap(viewer, page.build());
        else Screens.push(viewer, page.build());
    }

    // a held pick dropped anywhere but a slot that takes it is gone, not handed back
    private static Screen.Builder window(String title) {
        return Screen.rows(6).title(Component.text(title))
                .screenClicks((v, click) -> {
                    drop(v);
                    return false;
                })
                .ownClicks((v, click) -> {
                    if (v.getInventory().getCursorItem().getTag(HELD) == null) return true;
                    drop(v);
                    return false;
                });
    }

    private static void categories(@Nullable Game game, Screen.Builder screen) {
        for (int i = 0; i < CATEGORIES.size(); i++) {
            Category category = CATEGORIES.get(i);
            screen.button(10 + i, pickable(category), (v, click) -> {
                if (click instanceof Click.Right && category.tab() != null) {
                    drop(v);
                    items(game, v, category, false);
                } else {
                    v.getInventory().setCursorItem(held(category.role(), pickable(category)));
                }
            });
        }
    }

    private static ItemStack tab(Player viewer, boolean after) {
        boolean open = after == Boolean.TRUE.equals(viewer.getTag(AFTER));
        Layouts mine = HotbarOrders.of(viewer);
        List<Component> lore = new ArrayList<>();
        if (!after) {
            lore.add(Items.gray("The layout you play with."));
        } else if (mine.splits()) {
            lore.add(Items.gray("Takes over the moment your bed breaks."));
            lore.add(Component.empty());
            lore.add(Component.text("Right Click to turn it off!", NamedTextColor.YELLOW));
        } else {
            lore.add(Items.gray("Off: the same layout all game."));
            lore.add(Component.empty());
            lore.add(Component.text("Click to give it its own!", NamedTextColor.YELLOW));
        }
        if (open) lore.add(Component.text("Editing", NamedTextColor.AQUA));
        else if (!after || mine.splits()) lore.add(Component.text("Click to edit!", NamedTextColor.YELLOW));
        ItemStack item = Items.named(after ? Material.BLACK_BED : Material.RED_BED,
                Component.text(after ? "After Your Bed Breaks" : "With Your Bed", NamedTextColor.GREEN));
        return Items.bare(Items.lore(open ? item.withGlowing(true) : item, lore));
    }

    private static ItemStack pickable(Category category) {
        List<Component> lore = new ArrayList<>(List.of(Items.gray("Drag this to a hotbar slot below to"),
                Items.gray("put it first there, for purchases"), Items.gray("and on spawn."), Component.empty(),
                Component.text("Click to drag!", NamedTextColor.YELLOW)));
        if (category.tab() != null) lore.add(Component.text("Right Click for single items!", NamedTextColor.YELLOW));
        return Items.bare(Items.lore(Items.named(category.icon(), Component.text(category.name(), NamedTextColor.GREEN)), lore));
    }

    private static ItemStack divider(String above, String below) {
        return Items.lore(Items.named(Material.GRAY_STAINED_GLASS_PANE, Component.text("⬆ ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(above, NamedTextColor.GRAY))),
                List.of(Component.text("⬇ ", NamedTextColor.DARK_GRAY).append(Component.text(below, NamedTextColor.GRAY))));
    }

    private static ItemStack goBack(String to) {
        return Items.lore(Items.named(Material.ARROW, Component.text("Go Back", NamedTextColor.GREEN)), List.of(Items.gray(to)));
    }

    private static ItemStack held(String pick, ItemStack shown) {
        return shown.withTag(HELD, pick).withTag(InventorySync.CURSOR_TOKEN, true);
    }

    private static ItemStack slotItem(@Nullable Game game, Player viewer, int hotbar) {
        List<String> picks = editing(viewer).get(hotbar);
        if (picks.isEmpty()) {
            return Items.bare(Items.lore(Items.named(Material.LIGHT_GRAY_STAINED_GLASS_PANE, Component.text("Hotbar Slot " + (hotbar + 1), NamedTextColor.GREEN)),
                    List.of(Items.gray("Nothing picked yet."), Component.empty(),
                            Component.text("Drop a pick here!", NamedTextColor.YELLOW),
                            Component.text("Click to set the order!", NamedTextColor.YELLOW))));
        }
        List<Component> lore = new ArrayList<>();
        for (int i = 0; i < picks.size(); i++) lore.add(Items.gray((i + 1) + ". " + name(game, viewer, picks.get(i))));
        lore.add(Component.empty());
        lore.add(Component.text("Click to set the order!", NamedTextColor.YELLOW));
        lore.add(Component.text("Drop a pick here to put it first!", NamedTextColor.YELLOW));
        lore.add(Component.text("Right Click to remove " + name(game, viewer, picks.getFirst()) + "!", NamedTextColor.YELLOW));
        ItemStack first = Items.named(shown(game, viewer, picks.getFirst()), Component.text("Hotbar Slot " + (hotbar + 1), NamedTextColor.GREEN));
        return Items.bare(Items.lore(Items.counted(first, picks.size()), lore));
    }

    private static ItemStack orderItem(@Nullable Game game, Player viewer, int hotbar, int at) {
        List<String> picks = editing(viewer).get(hotbar);
        if (at >= picks.size()) {
            return Items.bare(Items.lore(Items.named(Material.LIGHT_GRAY_STAINED_GLASS_PANE, Component.text("Empty", NamedTextColor.GRAY)),
                    List.of(Component.text("Drop a pick here!", NamedTextColor.YELLOW))));
        }
        String pick = picks.get(at);
        return Items.bare(Items.lore(Items.named(shown(game, viewer, pick), Component.text(name(game, viewer, pick), NamedTextColor.GREEN)),
                List.of(Items.gray(at == 0 ? "First: takes this slot over the rest." : "Here when nothing before it is."), Component.empty(),
                        Component.text("Click to pick up!", NamedTextColor.YELLOW),
                        Component.text("Right Click to remove!", NamedTextColor.YELLOW))));
    }

    private static ItemStack shown(@Nullable Game game, Player viewer, String pick) {
        Category category = category(pick);
        if (category != null) return ItemStack.of(category.icon());
        Ware ware = ware(pick);
        return ware != null ? Wares.icon(ware, game, viewer).withAmount(1) : ItemStack.of(Material.BARRIER);
    }

    // a ware a hotbar order names is sold as goods, whose name reads no tier off a game
    private static String name(@Nullable Game game, Player viewer, String pick) {
        Category category = category(pick);
        if (category != null) return category.name() + " (any)";
        Ware ware = ware(pick);
        return ware != null ? ware.name(game, viewer) : pick;
    }

    private static @Nullable Category category(String pick) {
        for (Category category : CATEGORIES) if (category.role().equals(pick)) return category;
        return null;
    }

    private static @Nullable Ware ware(String pick) {
        return pick.startsWith(HotbarOrders.WARE) ? Wares.of(pick.substring(HotbarOrders.WARE.length())) : null;
    }

    // the layout this viewer has open: the after-bed one only while that tab is
    private static List<List<String>> editing(Player viewer) {
        Layouts mine = HotbarOrders.of(viewer);
        List<List<String>> layout = Boolean.TRUE.equals(viewer.getTag(AFTER)) && mine.splits() ? mine.afterBed() : mine.main();
        List<List<String>> out = new ArrayList<>(layout);
        while (out.size() < HotbarOrders.SLOTS) out.add(List.of());
        return out;
    }

    private static void edit(Player viewer, int hotbar, UnaryOperator<List<String>> change) {
        Layouts mine = HotbarOrders.of(viewer);
        boolean after = Boolean.TRUE.equals(viewer.getTag(AFTER)) && mine.splits();
        List<List<String>> layout = editing(viewer);
        layout.set(hotbar, List.copyOf(change.apply(layout.get(hotbar))));
        HotbarOrders.set(viewer, after ? new Layouts(mine.main(), layout) : new Layouts(layout, mine.afterBed()));
        Screens.repaint(viewer);
    }

    private static List<List<String>> copy(List<List<String>> layout) {
        return layout.stream().<List<String>>map(List::copyOf).toList();
    }

    // a swapped window drops the cursor, and a held pick is the window's, never the player's
    private static void drop(Player viewer) {
        if (viewer.getInventory().getCursorItem().getTag(HELD) != null) viewer.getInventory().setCursorItem(ItemStack.AIR);
    }
}
