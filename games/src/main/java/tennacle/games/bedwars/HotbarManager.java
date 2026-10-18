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
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The Hotbar Manager: picks in order for each hotbar slot, a kind or one shop item, the first one held going there.
 * Hypixel's window shape (captured 2026-09-24 and 27) with our ordered slots and an optional after-bed layout.
 */
final class HotbarManager {

    record Category(String role, String name, Material icon) {}

    // Utility wears a crafting table, not Hypixel's TNT, so it never reads as the TNT pick
    static final List<Category> CATEGORIES = List.of(
            new Category("blocks", "Blocks", Material.TERRACOTTA),
            new Category("melee", "Melee", Material.GOLDEN_SWORD),
            new Category("pickaxe", "Pickaxe", Material.IRON_PICKAXE),
            new Category("axe", "Axe", Material.IRON_AXE),
            new Category("shears", "Shears", Material.SHEARS),
            new Category("ranged", "Ranged", Material.BOW),
            new Category("potions", "Potions", Material.BREWING_STAND),
            new Category("utility", "Utility", Material.CRAFTING_TABLE));

    private static final Tag<String> HELD = Tag.String("bedwars:hotbar-pick");
    private static final Tag<Boolean> AFTER = Tag.Transient("bedwars:hotbar-after");

    private HotbarManager() {}

    static void open(Game game, Player viewer) {
        viewer.removeTag(AFTER);
        // the viewer's own inventory is theirs to arrange, but a held pick must never land in it
        Screen.Builder manager = Screen.rows(6).title(Component.text("Hotbar Manager"))
                .ownClicks((v, click) -> v.getInventory().getCursorItem().getTag(HELD) == null);
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
        for (int i = 0; i < CATEGORIES.size(); i++) {
            Category category = CATEGORIES.get(i);
            manager.button(10 + i, pickable(category), (v, click) -> v.getInventory().setCursorItem(held(category.role(), pickable(category))));
        }
        manager.button(9, oneItem(), (v, click) -> { // the kinds keep Hypixel's slots 10-17
            drop(v);
            choose(game, v, pick -> v.getInventory().setCursorItem(held(pick, shown(game, v, pick))));
        });
        ItemStack pane = Items.lore(Items.named(Material.GRAY_STAINED_GLASS_PANE, Component.text("⬆ ", NamedTextColor.DARK_GRAY)
                        .append(Component.text("Picks", NamedTextColor.GRAY))),
                List.of(Component.text("⬇ ", NamedTextColor.DARK_GRAY).append(Component.text("Hotbar", NamedTextColor.GRAY))));
        manager.row(2, pane);
        for (int slot = 0; slot < HotbarOrders.SLOTS; slot++) {
            int hotbar = slot;
            manager.button(27 + slot, v -> slotItem(game, v, hotbar), (v, click) -> {
                String pick = v.getInventory().getCursorItem().getTag(HELD);
                if (pick == null) {
                    order(game, v, hotbar);
                    return;
                }
                v.getInventory().setCursorItem(ItemStack.AIR);
                edit(v, hotbar, picks -> {
                    List<String> out = new ArrayList<>(List.of(pick));
                    picks.stream().filter(p -> !p.equals(pick)).forEach(out::add);
                    return out.subList(0, Math.min(out.size(), HotbarOrders.PICKS));
                });
                Screens.repaint(v);
            });
        }
        manager.button(48, Items.lore(Items.named(Material.ARROW, Component.text("Go Back", NamedTextColor.GREEN)),
                List.of(Items.gray("To Quick Buy"))), (v, click) -> {
            drop(v);
            ItemShop.open(game, v, Tab.QUICK_BUY);
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
        Screens.open(viewer, manager.build());
    }

    // one slot's order in a row, first on the left
    private static void order(Game game, Player viewer, int hotbar) {
        Screen.Builder screen = Screen.rows(3).title(Component.text("Hotbar Slot " + (hotbar + 1)));
        for (int i = 0; i < HotbarOrders.PICKS; i++) {
            int at = i;
            screen.button(9 + i, v -> {
                List<String> picks = editing(v).get(hotbar);
                if (at >= picks.size()) return ItemStack.AIR;
                return Items.bare(Items.lore(Items.named(shown(game, v, picks.get(at)), Component.text(name(game, v, picks.get(at)), NamedTextColor.GREEN)),
                        List.of(Items.gray(at == 0 ? "First: takes this slot over the rest." : "Here when nothing above it is."), Component.empty(),
                                Component.text("Click to move it earlier!", NamedTextColor.YELLOW),
                                Component.text("Shift Click to move it later!", NamedTextColor.YELLOW),
                                Component.text("Right Click to take it off!", NamedTextColor.YELLOW))));
            }, (v, click) -> edit(v, hotbar, picks -> {
                if (at >= picks.size()) return picks;
                List<String> out = new ArrayList<>(picks);
                if (click instanceof Click.Right) out.remove(at);
                else if (click instanceof Click.LeftShift && at + 1 < out.size()) out.add(at + 1, out.remove(at));
                else if (click instanceof Click.Left && at > 0) out.add(at - 1, out.remove(at));
                return out;
            }));
        }
        screen.button(21, Items.lore(Items.named(Material.ARROW, Component.text("Go Back", NamedTextColor.GREEN)),
                List.of(Items.gray("To Hotbar Manager"))), (v, click) -> Screens.back(v));
        screen.button(22, Items.lore(Items.named(Material.LIME_DYE, Component.text("Add a Pick", NamedTextColor.GREEN)),
                List.of(Items.gray("A kind or one item, at the end."), Component.empty(),
                        Component.text("Click to choose!", NamedTextColor.YELLOW))), (v, click) -> {
            if (editing(v).get(hotbar).size() >= HotbarOrders.PICKS) {
                v.sendMessage(Component.text("That slot holds " + HotbarOrders.PICKS + " picks already!", NamedTextColor.RED));
                return;
            }
            choose(game, v, pick -> edit(v, hotbar, picks -> {
                List<String> out = new ArrayList<>(picks);
                out.remove(pick);
                out.add(pick);
                return out;
            }));
        });
        screen.button(23, Items.lore(Items.named(Material.BARRIER, Component.text("Clear", NamedTextColor.RED)),
                List.of(Items.gray("Empties this slot's order."), Component.empty(),
                        Component.text("Click to clear!", NamedTextColor.YELLOW))), (v, click) -> edit(v, hotbar, picks -> List.of()));
        Screens.push(viewer, screen.build());
    }

    // every kind, then every shop item; the choice goes back to the screen underneath
    private static void choose(Game game, Player viewer, Consumer<String> chosen) {
        Screen.Builder screen = Screen.rows(6).title(Component.text("Choose a Pick"));
        for (int i = 0; i < CATEGORIES.size(); i++) {
            Category category = CATEGORIES.get(i);
            screen.button(i, Items.bare(Items.lore(Items.named(category.icon(), Component.text(category.name(), NamedTextColor.GREEN)),
                    List.of(Items.gray("Any " + category.name().toLowerCase() + " item."), Component.empty(),
                            Component.text("Click to choose!", NamedTextColor.YELLOW)))), (v, click) -> {
                Screens.back(v);
                chosen.accept(category.role());
                Screens.repaint(v);
            });
        }
        List<Ware> wares = Wares.pickable();
        for (int i = 0; i < Math.min(wares.size(), 36); i++) {
            Ware ware = wares.get(i);
            String pick = HotbarOrders.WARE + ware.offer().id();
            screen.button(9 + i, v -> Items.bare(Items.lore(Items.named(ware.icon(game, v), Component.text(ware.name(game, v), NamedTextColor.GREEN)),
                    List.of(Items.gray("Just this item."), Component.empty(), Component.text("Click to choose!", NamedTextColor.YELLOW)))), (v, click) -> {
                Screens.back(v);
                chosen.accept(pick);
                Screens.repaint(v);
            });
        }
        screen.button(49, Items.lore(Items.named(Material.ARROW, Component.text("Go Back", NamedTextColor.GREEN)), List.of()),
                (v, click) -> Screens.back(v));
        Screens.push(viewer, screen.build());
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
        return Items.bare(Items.lore(Items.named(category.icon(), Component.text(category.name(), NamedTextColor.GREEN)),
                List.of(Items.gray("Drag this to a hotbar slot below to"), Items.gray("put it first there, for purchases"),
                        Items.gray("and on spawn."), Component.empty(),
                        Component.text("Click to drag!", NamedTextColor.YELLOW))));
    }

    private static ItemStack oneItem() {
        return Items.bare(Items.lore(Items.named(Material.ITEM_FRAME, Component.text("One Item", NamedTextColor.GREEN)),
                List.of(Items.gray("A single item instead of a kind:"), Items.gray("Wool, a Fireball, a Stone Sword."), Component.empty(),
                        Component.text("Click to choose from the shop!", NamedTextColor.YELLOW))));
    }

    private static ItemStack held(String pick, ItemStack shown) {
        return shown.withTag(HELD, pick).withTag(InventorySync.CURSOR_TOKEN, true);
    }

    private static ItemStack slotItem(Game game, Player viewer, int hotbar) {
        List<String> picks = editing(viewer).get(hotbar);
        if (picks.isEmpty()) {
            return Items.bare(Items.lore(Items.named(Material.LIGHT_GRAY_STAINED_GLASS_PANE, Component.text("Hotbar Slot " + (hotbar + 1), NamedTextColor.GREEN)),
                    List.of(Items.gray("Nothing picked yet."), Component.empty(),
                            Component.text("Drop a pick here!", NamedTextColor.YELLOW))));
        }
        List<Component> lore = new ArrayList<>();
        for (int i = 0; i < picks.size(); i++) lore.add(Items.gray((i + 1) + ". " + name(game, viewer, picks.get(i))));
        lore.add(Component.empty());
        lore.add(Component.text("Click to set the order!", NamedTextColor.YELLOW));
        lore.add(Component.text("Drop a pick here to put it first!", NamedTextColor.YELLOW));
        ItemStack first = Items.named(shown(game, viewer, picks.getFirst()), Component.text("Hotbar Slot " + (hotbar + 1), NamedTextColor.GREEN));
        return Items.bare(Items.lore(Items.counted(first, picks.size()), lore));
    }

    private static ItemStack shown(Game game, Player viewer, String pick) {
        Category category = category(pick);
        if (category != null) return ItemStack.of(category.icon());
        Ware ware = ware(pick);
        return ware != null ? ware.icon(game, viewer).withAmount(1) : ItemStack.of(Material.BARRIER);
    }

    private static String name(Game game, Player viewer, String pick) {
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
