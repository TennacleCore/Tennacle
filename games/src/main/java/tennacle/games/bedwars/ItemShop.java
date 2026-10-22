package tennacle.games.bedwars;

import tennacle.games.bedwars.Wares.Tab;
import tennacle.games.bedwars.Wares.Ware;
import tennacle.games.shop.Favorites;
import tennacle.games.shop.Offer;
import tennacle.games.shop.Price;
import tennacle.games.shop.Refusal;
import tennacle.games.shop.Sale;
import tennacle.games.shop.Shop;

import io.github.term4.canvas.screen.Items;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.roster.Slot;
import net.minestom.server.tag.Tag;
import tennacle.games.Match;
import tennacle.games.beds.Beds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The item shop as Hypixel draws it (captured 2026-09-24 and 27): a six-row chest a tab, the tabs across the top
 * over a row of panes, the wares in the middle three rows. A tab opens a new window, a purchase repaints the
 * open one. A number key over a ware buys it into that hotbar slot. Quick Buy is each player's own 21 pins.
 * With no game ({@link #editor}) nothing is for sale: a click pins, the way a shift click does in a game.
 */
final class ItemShop {

    static final String FRONT = "bedwars:items";
    /** A second Quick Buy for once the player's bed is gone; unset or empty is off. */
    static final String AFTER_FRONT = "bedwars:items:after";
    // which of the two the open screen shows and edits
    private static final Tag<Boolean> AFTER = Tag.Transient("bedwars:quick-buy-after");
    static final int[] GRID = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    private ItemShop() {}

    static void open(Game game, Player viewer, Tab tab) {
        Screens.open(viewer, page(game, tab));
    }

    /** From the keeper: the Quick Buy in play, the after-bed one once the bed is gone. */
    static void enter(Game game, Player viewer) {
        String team = Match.teamOf(game, viewer);
        viewer.setTag(AFTER, splits(viewer) && team != null && !Beds.stands(game, team));
        open(game, viewer, Tab.QUICK_BUY);
    }

    /** Quick Buy to arrange outside a game, pushed over the current screen. */
    static void editor(Player viewer) {
        viewer.removeTag(AFTER);
        Screens.push(viewer, page(null, Tab.QUICK_BUY));
    }

    // the editor swaps its pages in place, so Go Back still leads where it was opened from
    static void show(@Nullable Game game, Player viewer, Tab tab) {
        if (game == null) Screens.swap(viewer, page(null, tab));
        else open(game, viewer, tab);
    }

    private static Screen page(@Nullable Game game, Tab open) {
        Screen.Builder page = Screen.rows(6).title(Component.text(open.title));
        for (Tab tab : Tab.values()) {
            if (game == null && tab == Tab.ROTATING) continue;
            page.button(tab.ordinal(), viewer -> tabIcon(tab, tab == open), (viewer, click) -> show(game, viewer, tab));
            page.button(9 + tab.ordinal(), viewer -> pane(tab == open), (viewer, click) -> show(game, viewer, tab));
        }
        for (int i = 0; i < GRID.length; i++) {
            int index = i;
            page.button(GRID[i], viewer -> icon(game, viewer, open, index), (viewer, click) -> press(game, viewer, open, index, click));
        }
        if (open == Tab.QUICK_BUY) {
            page.button(47, viewer -> layout(viewer, false), (viewer, click) -> {
                viewer.removeTag(AFTER);
                Screens.repaint(viewer);
            });
            page.button(51, viewer -> layout(viewer, true), (viewer, click) -> {
                if (click instanceof Click.Right) {
                    Favorites.set(viewer, AFTER_FRONT, List.of());
                    viewer.removeTag(AFTER);
                } else {
                    if (!splits(viewer)) Favorites.set(viewer, AFTER_FRONT, mainPins(viewer));
                    viewer.setTag(AFTER, true);
                }
                Screens.repaint(viewer);
            });
        }
        if (game == null) {
            page.button(49, Items.lore(Items.named(Material.ARROW, Component.text("Go Back", NamedTextColor.GREEN)), List.of()),
                    (viewer, click) -> Screens.back(viewer));
        } else if (open == Tab.QUICK_BUY) {
            page.button(45, Items.bare(Items.lore(Items.named(Material.COMPASS, Component.text("Tracker Shop", NamedTextColor.GREEN)),
                    Compass.trackerLore())), (viewer, click) -> TrackerShop.open(game, viewer, false));
            page.button(53, Items.lore(Items.named(Material.BLAZE_POWDER, Component.text("Hotbar Manager", NamedTextColor.GREEN)),
                    List.of(Items.gray("Edit preferred slots for your items"), Items.gray("per category."), Component.empty(),
                            Component.text("Click to edit!", NamedTextColor.YELLOW))),
                    (viewer, click) -> HotbarManager.open(game, viewer));
        }
        if (game != null && open == Tab.ROTATING) {
            page.item(49, Items.lore(Items.named(Material.PAPER, Component.text("What are Rotating Items?", NamedTextColor.GREEN)),
                    Items.gray("Rotating Items are items that are", "only available for a limited amount of",
                            "time. They may disappear and be", "replaced with another temporary", "item at any time.")));
        }
        return page.build();
    }

    private static ItemStack tabIcon(Tab tab, boolean open) {
        ItemStack icon = Items.named(tab.icon, Component.text(tab.title,
                tab == Tab.QUICK_BUY ? NamedTextColor.AQUA : NamedTextColor.GREEN));
        if (!open) icon = Items.lore(icon, List.of(Component.text("Click to view!", NamedTextColor.YELLOW)));
        return Items.bare(icon);
    }

    static ItemStack pane(boolean lit) {
        ItemStack pane = Items.named(lit ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                Component.text("⬆ ", NamedTextColor.DARK_GRAY).append(Component.text("Categories", NamedTextColor.GRAY)));
        return Items.lore(pane, List.of(Component.text("⬇ ", NamedTextColor.DARK_GRAY)
                .append(Component.text("Items", NamedTextColor.GRAY))));
    }

    // the bed on the one the player plays with, the black bed on the one that takes over when it breaks
    private static ItemStack layout(Player viewer, boolean after) {
        boolean open = after == editingAfter(viewer);
        List<Component> lore = new ArrayList<>();
        if (!after) {
            lore.add(Items.gray("The Quick Buy you play with."));
        } else if (splits(viewer)) {
            lore.add(Items.gray("Takes over the moment your bed breaks."));
            lore.add(Component.empty());
            lore.add(Component.text("Right Click to turn it off!", NamedTextColor.YELLOW));
        } else {
            lore.add(Items.gray("Off: one Quick Buy for the whole game."));
            lore.add(Component.empty());
            lore.add(Component.text("Click to give it its own!", NamedTextColor.YELLOW));
        }
        if (open) lore.add(Component.text("Showing", NamedTextColor.AQUA));
        else if (!after || splits(viewer)) lore.add(Component.text("Click to show!", NamedTextColor.YELLOW));
        ItemStack item = Items.named(after ? Material.BLACK_BED : Material.RED_BED,
                Component.text(after ? "After Your Bed Breaks" : "With Your Bed", NamedTextColor.GREEN));
        return Items.bare(Items.lore(open ? item.withGlowing(true) : item, lore));
    }

    static boolean splits(Player viewer) {
        List<String> after = Favorites.of(viewer, AFTER_FRONT);
        return after != null && after.size() == GRID.length;
    }

    private static boolean editingAfter(Player viewer) {
        return Boolean.TRUE.equals(viewer.getTag(AFTER)) && splits(viewer);
    }

    /** The Quick Buy on screen: the after-bed one while it's the one showing. */
    static List<String> pins(Player viewer) {
        return editingAfter(viewer) ? Favorites.of(viewer, AFTER_FRONT) : mainPins(viewer);
    }

    private static List<String> mainPins(Player viewer) {
        List<String> mine = Favorites.of(viewer, FRONT);
        if (mine == null || mine.size() != GRID.length) return Wares.QUICK_BUY;
        return mine;
    }

    static void pin(Player viewer, int index, String id) {
        List<String> next = new ArrayList<>(pins(viewer));
        next.set(index, id);
        Favorites.set(viewer, editingAfter(viewer) ? AFTER_FRONT : FRONT, next);
    }

    private static @Nullable Ware at(Player viewer, Tab tab, int index) {
        if (tab == Tab.QUICK_BUY) return Wares.of(pins(viewer).get(index));
        List<Ware> wares = Wares.in(tab);
        return index < wares.size() ? wares.get(index) : null;
    }

    private static ItemStack icon(@Nullable Game game, Player viewer, Tab tab, int index) {
        Ware ware = at(viewer, tab, index);
        if (ware == null) return tab == Tab.QUICK_BUY ? emptySlot() : ItemStack.AIR;
        List<Component> lore = header(game, viewer, ware);
        if (ware.upgradable()) {
            lore.addAll(Items.gray("This is an upgradable item.", "It will lose 1 tier upon death!"));
            lore.add(Component.empty());
            lore.addAll(Items.gray("You will permanently respawn with at", "least the lowest tier."));
            lore.add(Component.empty());
        }
        if (tab == Tab.QUICK_BUY) {
            lore.add(Component.text("Shift Click to remove from Quick Buy!", NamedTextColor.AQUA));
        } else if (game == null) {
            lore.add(Component.text("Click to add to Quick Buy!", NamedTextColor.YELLOW));
        } else if (tab != Tab.ROTATING) {
            lore.add(Component.text("Shift Click to add to Quick Buy", NamedTextColor.AQUA));
        }
        if (game != null) lore.add(last(game, viewer, ware));
        return face(game, viewer, ware, lore);
    }

    static List<Component> header(@Nullable Game game, Player viewer, Ware ware) {
        List<Component> lore = new ArrayList<>();
        if (game != null) prices(game, viewer, ware, lore);
        if (!lore.isEmpty()) lore.add(Component.empty());
        if (!ware.description().isEmpty()) {
            lore.addAll(ware.description());
            lore.add(Component.empty());
        }
        return lore;
    }

    private static void prices(Game game, Player viewer, Ware ware, List<Component> lore) {
        if (!owned(game, viewer, ware)) {
            Price price = ware.offer().price(game, viewer);
            lore.add(Component.text("Cost: ", NamedTextColor.GRAY).append(Component.text(
                    price.amount() + " " + price.currency().name(price.amount()), price.currency().color())));
        }
        String upgraded = upgraded(game, viewer, ware);
        if (upgraded != null) {
            lore.add(Component.text("Upgraded: ", NamedTextColor.GRAY).append(Component.text(upgraded, NamedTextColor.YELLOW)));
        }
        if (ware.upgradable()) {
            lore.add(Component.text("Tier: ", NamedTextColor.GRAY)
                    .append(Component.text(roman(ware.tier(game, viewer)), NamedTextColor.YELLOW)));
        }
    }

    // captured: swords and axes carry the team's Sharpness, armor its Protection
    private static @Nullable String upgraded(Game game, Player viewer, Ware ware) {
        Slot seat = game.roster().slotOf(viewer);
        if (seat == null) return null;
        String id = ware.offer().id();
        if (id.endsWith("-sword") || id.equals("axe")) {
            int level = Upgrades.level(game, seat, Upgrades.SHARPNESS);
            return level > 0 ? "Sharpness " + roman(level) : null;
        }
        if (ware.tab() == Tab.ARMOR) {
            int level = Upgrades.level(game, seat, Upgrades.PROTECTION);
            return level > 0 ? "Protection " + roman(level) : null;
        }
        return null;
    }

    private static Component last(Game game, Player viewer, Ware ware) {
        if (owned(game, viewer, ware)) return Component.text("UNLOCKED", NamedTextColor.GREEN);
        Price price = ware.offer().price(game, viewer);
        if (affordable(viewer, price)) return Component.text("Click to purchase!", NamedTextColor.YELLOW);
        return Component.text("You don't have enough " + price.currency().name(price.amount()) + "!", NamedTextColor.RED);
    }

    // captured: an owned permanent keeps a red name
    static ItemStack face(@Nullable Game game, Player viewer, Ware ware, List<Component> lore) {
        if (game == null) {
            return Items.bare(Items.lore(Items.named(Wares.icon(ware, null, viewer),
                    Component.text(ware.names().getFirst(), NamedTextColor.GREEN)), lore));
        }
        Price price = ware.offer().price(game, viewer);
        boolean green = !owned(game, viewer, ware) && affordable(viewer, price);
        ItemStack icon = Items.named(ware.icon(game, viewer),
                Component.text(ware.name(game, viewer), green ? NamedTextColor.GREEN : NamedTextColor.RED));
        return Items.bare(Items.lore(icon, lore));
    }

    static boolean owned(Game game, Player viewer, Ware ware) {
        return ware.permanent() && ware.offer().refusal(game, viewer) == Refusal.OWNED;
    }

    private static boolean affordable(Player viewer, Price price) {
        return price.currency().held(viewer) >= price.amount();
    }

    static ItemStack emptySlot() {
        return Items.lore(Items.named(Material.RED_STAINED_GLASS_PANE, Component.text("Empty slot!", NamedTextColor.RED)),
                List.of(Component.text("This is a Quick Buy Slot! ", NamedTextColor.GRAY)
                                .append(Component.text("Shift Click", NamedTextColor.AQUA)),
                        Items.gray("any item in the shop to add it here.")));
    }

    private static void press(@Nullable Game game, Player viewer, Tab tab, int index, Click click) {
        Ware ware = at(viewer, tab, index);
        if (click instanceof Click.Double) return; // the client's extra packet after two quick clicks; captured buying nothing
        if (game == null) {
            if (ware == null || click instanceof Click.HotbarSwap) return;
            if (tab != Tab.QUICK_BUY) QuickBuyPicker.open(null, viewer, ware);
            else if (click instanceof Click.LeftShift || click instanceof Click.RightShift || click instanceof Click.Right) {
                pin(viewer, index, "");
                Screens.repaint(viewer);
            }
            return;
        }
        if (ware == null) {
            if (tab != Tab.QUICK_BUY) return;
            Lines.emptyQuickBuySlot(game).forEach(viewer::sendMessage);
            viewer.playSound(Lines.REFUSED);
            return;
        }
        switch (click) {
            case Click.LeftShift ignored -> shift(game, viewer, tab, index, ware);
            case Click.RightShift ignored -> shift(game, viewer, tab, index, ware);
            case Click.Left ignored -> buy(game, viewer, ware, -1);
            case Click.Right ignored -> buy(game, viewer, ware, -1);
            case Click.HotbarSwap swap -> buy(game, viewer, ware, swap.hotbarSlot());
            default -> {}
        }
    }

    private static void shift(Game game, Player viewer, Tab tab, int index, Ware ware) {
        if (tab == Tab.QUICK_BUY) {
            pin(viewer, index, "");
            Screens.repaint(viewer);
        } else if (tab != Tab.ROTATING) {
            QuickBuyPicker.open(game, viewer, ware);
        }
    }

    private static void buy(Game game, Player viewer, Ware ware, int into) {
        Shop shop = game.module(Shop.class);
        if (shop == null) return;
        String name = ware.name(game, viewer); // a tool's before the tier moves on
        Offer offer = ware.offer();
        Sale sale = shop.buy(viewer, offer, into);
        switch (sale) {
            case Sale.Sold sold -> Lines.bought(viewer, Lines.purchased(game, name));
            case Sale.Short missing -> Lines.refused(viewer, Lines.shortOf(game, missing.price(), missing.missing()));
            case Sale.Refused refused -> refuse(game, viewer, refused.refusal());
        }
        Screens.repaint(viewer);
    }

    // captured: a lower armor tier refuses without a sound
    private static void refuse(Game game, Player viewer, Refusal refusal) {
        if (refusal == Refusal.FULL) Lines.refused(viewer, Lines.full(game));
        else if (refusal == Refusal.OWNED) Lines.refused(viewer, Lines.owned(game));
        else if (refusal == Refusal.BEYOND) viewer.sendMessage(Lines.beyond(game));
    }

    static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> Integer.toString(n);
        };
    }
}
