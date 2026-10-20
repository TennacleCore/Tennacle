package tennacle.games.bedwars;

import io.github.term4.canvas.Canvas;
import io.github.term4.canvas.screen.Entry;
import io.github.term4.canvas.theme.Theme;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.games.Careers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** A player's Bed Wars stats, a category an item, for all of Bed Wars or one mode at a time. */
final class StatsScreen {

    private record Mode(String scope, String name, Material icon) {}

    private static final List<Mode> MODES = List.of(
            new Mode("bedwars", "All Modes", Material.RED_BED),
            new Mode("bedwars-solo", "Solo", Material.WOODEN_SWORD),
            new Mode("bedwars-doubles", "Doubles", Material.STONE_SWORD),
            new Mode("bedwars-3s", "3v3v3v3", Material.IRON_SWORD),
            new Mode("bedwars-4s", "4v4v4v4", Material.DIAMOND_SWORD));

    /** What a category item shows, off the stats of one mode. */
    private record Category(Material icon, String name, BiConsumer<Entry, Stats> facts, @Nullable More more) {

        Category(Material icon, String name, BiConsumer<Entry, Stats> facts) {
            this(icon, name, facts, null);
        }
    }

    private record Stats(Map<String, Long> all, String scope, Theme theme) {
        long n(String stat) {
            return all.getOrDefault(scope + ":" + stat, 0L);
        }

        // the scrims cards' two numbers: kills | final kills
        Component pair(String a, String b) {
            return theme.value(count(n(a))).append(theme.dim(" | ")).append(theme.value(count(n(b))));
        }
    }

    /** A category a click takes further, to its own screen. */
    @FunctionalInterface
    private interface More {
        Screen open(UUID subject, String name, int mode);
    }

    // each paid in the shop's own price colors
    private record Resource(String id, NamedTextColor color) {}

    private static final List<Resource> RESOURCES = List.of(new Resource("iron", NamedTextColor.WHITE),
            new Resource("gold", NamedTextColor.GOLD), new Resource("diamond", NamedTextColor.AQUA),
            new Resource("emerald", NamedTextColor.DARK_GREEN));
    private static final String[] CAUSES = {"melee", "bow", "void", "fall", "fire", "explosion", "creature", "other"};

    private static final List<Category> CATEGORIES = List.of(
            new Category(Material.PAPER, "Overview", (e, s) -> e
                    .fact("Games Played", count(s.n("games"))).fact("Wins", count(s.n("wins"))).fact("Losses", count(s.n("losses")))
                    .fact("W/L Ratio", ratio(s.n("wins"), s.n("losses"))).fact("Winstreak", count(s.n("winstreak")))
                    .fact("Best Winstreak", count(s.n("best-winstreak"))).fact("Time Played", hours(s.n("time-played")))),
            new Category(Material.IRON_SWORD, "Kills", (e, s) -> e
                    .fact("Kills", count(s.n("kills"))).fact("Deaths", count(s.n("deaths"))).fact("K/D Ratio", ratio(s.n("kills"), s.n("deaths")))
                    .fact("Final Kills", count(s.n("final-kills"))).fact("Final Deaths", count(s.n("final-deaths")))
                    .fact("Final K/D Ratio", ratio(s.n("final-kills"), s.n("final-deaths"))).fact("Assists", count(s.n("assists")))),
            new Category(Material.BOW, "Kills By Method", (e, s) -> {
                e.text("Kills | Final Kills");
                for (String cause : CAUSES) e.fact(title(cause), s.pair(cause + "-kills", cause + "-final-kills"));
            }),
            new Category(Material.SKELETON_SKULL, "Deaths By Method", (e, s) -> {
                e.text("Deaths | Final Deaths");
                for (String cause : CAUSES) e.fact(title(cause), s.pair(cause + "-deaths", cause + "-final-deaths"));
            }),
            new Category(Material.RED_BED, "Beds", (e, s) -> e
                    .fact("Beds Broken", count(s.n("beds-broken"))).fact("Beds Lost", count(s.n("beds-lost")))
                    .fact("Bed Attempts", count(s.n("bed-attempts"))).fact("Failed Attempts", count(s.n("bed-attempts-failed")))
                    .fact("Time Digging Beds", seconds(s.n("bed-dig-ticks")))
                    .fact("Defense Blocks Broken", count(s.n("bed-defense-broken"))).fact("Defense Blocks Placed", count(s.n("bed-defense-placed")))
                    .fact("Bed Saves", count(s.n("bed-saves"))).fact("Saved By A Kill", count(s.n("bed-saves-by-kill")))
                    .fact("Saved By A Block", count(s.n("bed-saves-by-block")))
                    .fact("Time Guarding Bed", hours(s.n("time-guarding-bed")))),
            resource(Material.IRON_INGOT, "Iron", "iron"),
            resource(Material.GOLD_INGOT, "Gold", "gold"),
            resource(Material.DIAMOND, "Diamonds", "diamond"),
            resource(Material.EMERALD, "Emeralds", "emerald"),
            new Category(Material.GOLDEN_SWORD, "Combat", (e, s) -> e
                    .fact("Damage Dealt", hearts(s.n("damage-dealt"))).fact("Damage Taken", hearts(s.n("damage-taken")))
                    .fact("Hits", count(s.n("hits"))).fact("Hits Taken", count(s.n("hits-taken")))
                    .fact("Hits Blocked", count(s.n("hits-blocked"))).fact("Damage Blocked", hearts(s.n("damage-blocked")))
                    .fact("Arrows Shot", count(s.n("arrows-shot"))).fact("Arrows Hit", count(s.n("arrows-hit")))
                    .fact("Bow Accuracy", percent(s.n("arrows-hit"), s.n("arrows-shot")))),
            new Category(Material.GOLDEN_APPLE, "Items Used", (e, s) -> e
                    .fact("Golden Apples", count(s.n("golden-apples-eaten"))).fact("Potions", count(s.n("potions-drunk")))
                    .fact("Magic Milk", count(s.n("magic-milk-drunk"))).fact("Water Buckets", count(s.n("water-buckets-used")))
                    .fact("Speed Potions", count(s.n("speed-potions-drunk"))).fact("Jump Potions", count(s.n("jump-potions-drunk")))
                    .fact("Invisibility Potions", count(s.n("invisibility-potions-drunk")))
                    .fact("Ender Pearls", count(s.n("pearls-thrown"))).fact("Fireballs", count(s.n("fireballs-thrown")))
                    .fact("TNT", count(s.n("tnt-placed"))).fact("Bedbugs", count(s.n("silverfish-spawned")))
                    .fact("Dream Defenders", count(s.n("golems-spawned"))).fact("Bridge Eggs", count(s.n("bridge-eggs-thrown")))
                    .fact("Pop-up Towers", count(s.n("popup-towers-placed"))).fact("Sponges", count(s.n("sponges-placed")))),
            new Category(Material.CHEST, "Purchases", (e, s) -> e
                    .fact("Items", count(s.n("items-bought"))).fact("Permanent Items", count(s.n("permanent-items-bought")))
                    .fact("Team Upgrades", count(s.n("upgrades-bought"))).fact("Traps", count(s.n("traps-bought")))
                    .fact("Spent For The Team", spent(s, "for-team", s.theme().value("0"))).action("Click for every item!"), StatsScreen::purchases),
            new Category(Material.BRICKS, "Building", (e, s) -> e
                    .fact("Blocks Placed", count(s.n("blocks-placed"))).fact("Blocks Broken", count(s.n("blocks-broken")))),
            new Category(Material.COMPASS, "Time Spent", (e, s) -> e
                    .fact("At Their Base", hours(s.n("time-at-base"))).fact("At Mid", hours(s.n("time-at-mid")))
                    .fact("At Diamonds", hours(s.n("time-at-diamonds"))).fact("At Emeralds", hours(s.n("time-at-emeralds")))
                    .fact("At Other Bases", hours(s.n("time-at-other-bases")))
                    .fact("At Bases With A Bed", hours(s.n("time-at-bases-alive")))
                    .fact("At Bedless Bases", hours(s.n("time-at-bases-bedless")))
                    .fact("At Bases Of Teams Out", hours(s.n("time-at-bases-out")))));

    // rows of five under the modes, the last four either side of the middle
    private static final int[] SLOTS = {20, 21, 22, 23, 24, 29, 30, 31, 32, 33, 38, 39, 41, 42};

    private static final Map<UUID, Map<String, Long>> READ = new ConcurrentHashMap<>();
    private static final Set<UUID> READING = ConcurrentHashMap.newKeySet();

    private StatsScreen() {}

    static @NotNull Screen open(@NotNull UUID subject, @NotNull String name) {
        READ.remove(subject); // fresh each time the screen opens
        return screen(subject, name, 0);
    }

    private static Screen screen(UUID subject, String name, int mode) {
        // 1.8 cuts a window title at 32: a name and a label can overrun it, and the profile under says whose
        Screen.Builder screen = Screen.rows(6).title(Component.text("Bed Wars Stats")).back(48).close(49);
        for (int i = 0; i < MODES.size(); i++) {
            int index = i;
            Mode m = MODES.get(i);
            screen.press(2 + i, viewer -> Entry.of(m.icon()).name(m.name()).glint(index == mode)
                    .action(index == mode ? "Showing" : "Click to show").render(viewer), (viewer, click) -> {
                if (index != mode) Screens.swap(viewer, screen(subject, name, index));
                return null;
            });
        }
        for (int i = 0; i < CATEGORIES.size() && i < SLOTS.length; i++) {
            Category category = CATEGORIES.get(i);
            if (category.more() == null) screen.item(SLOTS[i], viewer -> category(viewer, subject, category, MODES.get(mode)));
            else screen.press(SLOTS[i], viewer -> category(viewer, subject, category, MODES.get(mode)), (viewer, click) -> {
                Screens.push(viewer, category.more().open(subject, name, mode));
                return null;
            });
        }
        return screen.build();
    }

    // a category a shop tab, then the team's upgrades and traps; each item with what it cost
    private static Screen purchases(UUID subject, String name, int mode) {
        Screen.Builder screen = Screen.rows(5).title(Component.text("Bed Wars Purchases")).back(39).close(40);
        List<Category> shown = new ArrayList<>();
        for (Wares.Tab tab : Wares.Tab.values()) {
            if (tab == Wares.Tab.QUICK_BUY || Wares.in(tab).isEmpty()) continue;
            shown.add(new Category(tab.icon, tab.title, (e, s) -> {
                for (Wares.Ware ware : Wares.in(tab)) ware(e, s, ware);
            }));
        }
        shown.add(new Category(Material.DIAMOND_CHESTPLATE, "Team Upgrades", StatsScreen::upgrades));
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 21, 22, 23};
        for (int i = 0; i < shown.size() && i < slots.length; i++) {
            Category category = shown.get(i);
            screen.item(slots[i], viewer -> category(viewer, subject, category, MODES.get(mode)));
        }
        return screen.build();
    }

    // bought | spent, a tier at a time where the shop sells them so
    private static void ware(Entry entry, Stats s, Wares.Ware ware) {
        String id = ware.offer().id();
        if (!ware.upgradable()) {
            entry.fact(ware.names().getFirst(), bought(s, id));
            return;
        }
        for (int tier = 1; tier <= ware.names().size(); tier++) entry.fact(ware.names().get(tier - 1), bought(s, id + "-" + tier));
    }

    // bought | paid, the price in its resource's color
    private static Component bought(Stats s, String what) {
        Component line = s.theme().value(count(s.n("bought-" + what)));
        Component paid = spent(s, "on-" + what, null);
        return paid == null ? line : line.append(s.theme().dim(" | ")).append(paid);
    }

    // what was paid, a number in each resource's color; {@code none} when nothing was
    private static Component spent(Stats s, String suffix, Component none) {
        Component out = null;
        for (Resource res : RESOURCES) {
            long n = s.n(res.id() + "-spent-" + suffix);
            if (n <= 0) continue;
            Component part = Component.text(count(n), res.color());
            out = out == null ? part : out.append(s.theme().dim(", ")).append(part);
        }
        return out != null ? out : none;
    }

    private static ItemStack category(Player viewer, UUID subject, Category category, Mode mode) {
        Entry entry = Entry.of(category.icon()).name(category.name()).kind(mode.name());
        Map<String, Long> all = READ.get(subject);
        if (all == null) {
            if (READING.add(subject)) {
                Careers.all(subject).whenComplete((read, failed) -> {
                    READ.put(subject, failed != null ? Map.of() : read);
                    READING.remove(subject);
                    Screens.repaint(viewer);
                });
            }
            return entry.text("Loading...").render(viewer);
        }
        category.facts().accept(entry, new Stats(all, mode.scope(), Canvas.screenThemeFor(viewer)));
        return entry.render(viewer);
    }

    private static Category resource(Material icon, String name, String res) {
        return new Category(icon, name, (e, s) -> {
            long collected = s.n(res + "-from-generators") + s.n(res + "-from-players") + s.n(res + "-stolen") + s.n(res + "-from-chests");
            e.fact("Collected", count(collected)).fact("From Generators", count(s.n(res + "-from-generators")));
            switch (res) {
                case "iron", "gold" -> e.fact("Own Forge", count(s.n(res + "-from-own-forge")))
                        .fact("Other Forges", count(s.n(res + "-from-other-forges")));
                case "diamond" -> e.fact("Own Generators", count(s.n(res + "-from-own-diamond-generators")))
                        .fact("Other Generators", count(s.n(res + "-from-other-diamond-generators")));
                default -> e.fact("Emerald Generators", count(s.n(res + "-from-emerald-generators")))
                        .fact("Own Forge", count(s.n(res + "-from-own-forge")));
            }
            e.fact("Picked Up From Drops", count(s.n(res + "-from-players"))).fact("Taken On Kills", count(s.n(res + "-stolen")))
                    .fact("Taken From Chests", count(s.n(res + "-from-chests"))).fact("Put In Chests", count(s.n(res + "-into-chests")))
                    .fact("Spent", count(s.n(res + "-spent"))).fact("Lost To Killers", count(s.n(res + "-lost-to-players")))
                    .fact("Lost To The Void", count(s.n(res + "-lost-to-void")))
                    .fact("Your Drops Blown Up", count(s.n(res + "-lost-to-explosions")))
                    .fact("Blown Up By You", count(s.n(res + "-destroyed")));
        });
    }

    // each upgrade's tiers by their own names, then the traps
    private static void upgrades(Entry entry, Stats s) {
        for (Upgrades.Upgrade upgrade : Upgrades.ALL) {
            String id = upgrade.offer().id();
            if (upgrade.names().size() <= 1) {
                entry.fact(upgrade.names().isEmpty() ? title(id) : upgrade.names().getFirst(), bought(s, id));
                continue;
            }
            for (int tier = 1; tier <= upgrade.names().size(); tier++) entry.fact(upgrade.names().get(tier - 1), bought(s, id + "-" + tier));
        }
        for (Traps.Kind trap : Traps.Kind.values()) entry.fact(trap.title, bought(s, trap.id));
    }

    private static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static String ratio(long over, long under) {
        return String.format(Locale.ROOT, "%.2f", under == 0 ? (double) over : (double) over / under);
    }

    private static String percent(long part, long whole) {
        return whole == 0 ? "0%" : String.format(Locale.ROOT, "%.1f%%", 100.0 * part / whole);
    }

    private static String hours(long seconds) {
        return seconds < 3600 ? (seconds / 60) + "m " + (seconds % 60) + "s" : (seconds / 3600) + "h " + (seconds / 60 % 60) + "m";
    }

    private static String seconds(long ticks) {
        return String.format(Locale.ROOT, "%,.1fs", ticks / 20.0);
    }

    private static String hearts(long tenths) {
        return String.format(Locale.ROOT, "%,.1f hearts", tenths / 20.0);
    }

    private static String title(String id) {
        StringBuilder out = new StringBuilder();
        for (String word : id.split("-")) out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        return out.toString();
    }
}
