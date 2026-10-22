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
import io.github.term4.grotto.vault.VaultKey;
import net.minestom.server.codec.Codec;
import net.minestom.server.tag.Tag;
import tennacle.PlayerPrefs;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The item shop as Hypixel draws it (captured 2026-09-24 and 27): a six-row chest a tab, the tabs across the top
 * over a row of panes, the wares in the middle three rows. A tab opens a new window, a purchase repaints the
 * open one. A number key over a ware buys it into that hotbar slot. Quick Buy is each player's own 21 pins, in nine
 * presets along the bottom: one in use, and optionally one that takes over once their bed is gone.
 * With no game ({@link #editor}) nothing is for sale: a click pins, the way a shift click does in a game.
 */
final class ItemShop {

    /** Preset 1's storefront; preset n is {@code FRONT:n}. */
    static final String FRONT = "bedwars:items";
    static final int PRESETS = 9;
    private static final int PRESET_ROW = 45;
    // the preset the open screen shows and edits
    private static final Tag<Integer> SHOWING = Tag.Transient("bedwars:quick-buy-showing");
    // "in use,after the bed" by preset number; 0 after the bed = none
    private static volatile PlayerPrefs.Entry<String, String> choice;
    static final int[] GRID = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    private ItemShop() {}

    static void open(Game game, Player viewer, Tab tab) {
        Screens.open(viewer, page(game, tab));
    }

    /** Registers the preset choice; at boot, before anyone logs in. */
    static synchronized void install() {
        if (choice == null) choice = PlayerPrefs.register(VaultKey.of("bedwars:quick-buy-presets", Codec.STRING, "1,0"));
    }

    /** From the keeper: the preset in play, the after-bed one once the bed is gone. */
    static void enter(Game game, Player viewer) {
        String team = Match.teamOf(game, viewer);
        int after = afterBed(viewer);
        viewer.setTag(SHOWING, after > 0 && team != null && !Beds.stands(game, team) ? after : inUse(viewer));
        open(game, viewer, Tab.QUICK_BUY);
    }

    /** Quick Buy to arrange outside a game, pushed over the current screen. */
    static void editor(Player viewer) {
        viewer.removeTag(SHOWING);
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
            for (int p = 1; p <= PRESETS; p++) {
                int preset = p;
                page.button(PRESET_ROW + p - 1, viewer -> preset(viewer, preset), (viewer, click) -> choose(viewer, preset, click));
            }
        }
        // the bottom row holds the presets on Quick Buy, so these sit beside the grid
        if (game == null) {
            page.button(18, Items.lore(Items.named(Material.ARROW, Component.text("Go Back", NamedTextColor.GREEN)), List.of()),
                    (viewer, click) -> Screens.back(viewer));
        } else if (open == Tab.QUICK_BUY) {
            page.button(18, Items.bare(Items.lore(Items.named(Material.COMPASS, Component.text("Tracker Shop", NamedTextColor.GREEN)),
                    Compass.trackerLore())), (viewer, click) -> TrackerShop.open(game, viewer, false));
            page.button(26, Items.lore(Items.named(Material.BLAZE_POWDER, Component.text("Hotbar Manager", NamedTextColor.GREEN)),
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

    // paper once saved, a dye while empty; the count is its number
    private static ItemStack preset(Player viewer, int preset) {
        boolean saved = saved(viewer, preset), showing = showing(viewer) == preset;
        List<Component> lore = new ArrayList<>();
        if (!saved) {
            lore.add(Items.gray("Empty. Click to start it as a copy"));
            lore.add(Items.gray("of the one showing."));
        } else {
            if (inUse(viewer) == preset) lore.add(Component.text("In use", NamedTextColor.GREEN));
            if (afterBed(viewer) == preset) lore.add(Component.text("Takes over when your bed breaks", NamedTextColor.RED));
            if (showing) lore.add(Component.text("Showing", NamedTextColor.AQUA));
            lore.add(Component.empty());
            if (inUse(viewer) != preset) lore.add(Component.text("Click to use!", NamedTextColor.YELLOW));
            lore.add(Component.text(afterBed(viewer) == preset ? "Right Click to stop using it after your bed breaks!"
                    : "Right Click to use it after your bed breaks!", NamedTextColor.YELLOW));
        }
        ItemStack item = Items.named(saved ? Material.PAPER : Material.GRAY_DYE,
                Component.text("Quick Buy " + preset, saved ? NamedTextColor.GREEN : NamedTextColor.GRAY)).withAmount(preset);
        return Items.bare(Items.lore(showing ? item.withGlowing(true) : item, lore));
    }

    private static void choose(Player viewer, int preset, Click click) {
        if (!saved(viewer, preset)) Favorites.set(viewer, front(preset), pins(viewer));
        if (click instanceof Click.Right) {
            int after = afterBed(viewer) == preset ? 0 : preset;
            setChoice(viewer, inUse(viewer), after);
            if (after > 0) viewer.setTag(SHOWING, preset);
        } else {
            setChoice(viewer, preset, afterBed(viewer));
            viewer.setTag(SHOWING, preset);
        }
        Screens.repaint(viewer);
    }

    static String front(int preset) {
        return preset == 1 ? FRONT : FRONT + ":" + preset;
    }

    private static boolean saved(Player viewer, int preset) {
        List<String> pins = Favorites.of(viewer, front(preset));
        return preset == 1 || pins != null && pins.size() == GRID.length;
    }

    /** The preset the player plays with. */
    static int inUse(Player viewer) {
        return part(viewer, 0, 1);
    }

    /** The preset that takes over once the bed is gone; 0 for none. */
    static int afterBed(Player viewer) {
        return part(viewer, 1, 0);
    }

    /** Anyone's in-use preset pins, online or not; null while it was never saved. */
    static CompletableFuture<@Nullable List<String>> loadInUse(UUID owner) {
        if (choice == null) return Favorites.load(owner, FRONT);
        return choice.load(owner).thenCompose(held -> Favorites.load(owner, front(part(held, 0, 1))));
    }

    private static int part(Player viewer, int at, int fallback) {
        return part(choice != null ? choice.of(viewer.getUuid()) : null, at, fallback);
    }

    private static int part(@Nullable String held, int at, int fallback) {
        if (held == null) return fallback;
        String[] parts = held.split(",");
        try {
            int n = at < parts.length ? Integer.parseInt(parts[at].trim()) : fallback;
            return n >= (at == 0 ? 1 : 0) && n <= PRESETS ? n : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void setChoice(Player viewer, int inUse, int afterBed) {
        if (choice != null) choice.set(viewer, inUse + "," + afterBed);
    }

    private static int showing(Player viewer) {
        Integer shown = viewer.getTag(SHOWING);
        return shown != null && saved(viewer, shown) ? shown : inUse(viewer);
    }

    /** The Quick Buy on screen. */
    static List<String> pins(Player viewer) {
        return pinsOf(viewer, showing(viewer));
    }

    /** {@code preset}'s pins; one never saved is Hypixel's own. */
    static List<String> pinsOf(Player viewer, int preset) {
        List<String> mine = Favorites.of(viewer, front(preset));
        if (mine == null || mine.size() != GRID.length) return Wares.QUICK_BUY;
        return mine;
    }

    static void pin(Player viewer, int index, String id) {
        List<String> next = new ArrayList<>(pins(viewer));
        next.set(index, id);
        Favorites.set(viewer, front(showing(viewer)), next);
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
