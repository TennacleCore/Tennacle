package tennacle.lobby.menus;

import io.github.term4.canvas.Canvas;
import io.github.term4.canvas.screen.Entry;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.matchmaking.Play;
import tennacle.games.matchmaking.Queues;
import tennacle.lobby.Counts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

/**
 * A mode's maps, from a right click: on a size in {@link PlayMenu} a map plays there, on a game in the duel picker
 * ({@link PlayerMenu}) the request names it; Random Map leaves it to the pool. Laid out as Hypixel's map selector:
 * seven a row over three rows, Random Map under them, Go Back at the bottom. Maps taken from a network's picker keep
 * its pages, one a category in its order, the grid widening as it does; previous and next walk the pages.
 */
final class MapMenu {

    /** {@code pick} gets a null map for Random Map, and answers a refusal or null once it went through. */
    record Use(String title, String kind, String action, BiConsumer<Entry, String> facts,
               BiFunction<Player, String, String> pick) {}

    private record Shelf(@Nullable String name, List<String> maps) {}

    private static final int[] MAPS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    // as Scrims widens a full page: the row under the grid either side of Random, then every row but the bottom
    private static final int[] WIDE = IntStream.concat(IntStream.of(MAPS), IntStream.of(37, 38, 42, 43)).toArray();
    private static final int[] FULL = IntStream.concat(IntStream.range(0, 39), IntStream.range(42, 45)).toArray();
    private static final int RANDOM = 40, BACK = 49, PREVIOUS = 45, NEXT = 53;

    private MapMenu() {}

    static boolean chooses(@NotNull Mode mode) {
        return mode.arena().names().size() > 1;
    }

    /** A size's maps: a pick plays there. */
    static void play(@NotNull Player viewer, @NotNull Mode mode, @NotNull String id) {
        String label = Queues.label(mode, id);
        open(viewer, mode, new Use(label, label, "► Click to Play", (entry, map) -> {
            if (Matches.joinable(mode)) entry.fact("Available Games", Matches.waitingOn(mode, map));
            else entry.fact("Queued", Queues.queued(id, map));
        }, (v, map) -> Play.start(v, id, map)));
    }

    static void open(@NotNull Player viewer, @NotNull Mode mode, @NotNull Use use) {
        Screens.push(viewer, page(mode, use, shelves(mode), 0, 0));
    }

    // the picker's pages in its order, the ranked pool after them, then any map no picker listed
    private static List<Shelf> shelves(Mode mode) {
        TreeMap<Integer, String> names = new TreeMap<>();
        TreeMap<Integer, List<String>> pages = new TreeMap<>();
        TreeMap<Integer, String> ranked = new TreeMap<>();
        String rankedName = null;
        List<String> unlisted = new ArrayList<>();
        Map<String, CompoundBinaryTag> listed = new HashMap<>();
        for (String map : mode.arena().names()) {
            CompoundBinaryTag picker = picker(mode, map);
            listed.put(map, picker);
            if (!picker.keySet().contains("page")) {
                unlisted.add(map);
                continue;
            }
            int page = picker.getInt("page");
            names.putIfAbsent(page, picker.getString("category"));
            pages.computeIfAbsent(page, p -> new ArrayList<>()).add(map);
            if (picker.getInt("ranked") > 0) {
                ranked.put(picker.getInt("ranked"), map);
                rankedName = picker.getString("rankedPage");
            }
        }
        unlisted.sort(Comparator.comparing(map -> Matches.mapName(mode, map)));
        if (pages.isEmpty()) return List.of(new Shelf(null, unlisted));
        List<Shelf> shelves = new ArrayList<>();
        pages.forEach((page, maps) -> {
            maps.sort(Comparator.comparingInt(map -> listed.get(map).getInt("order")));
            shelves.add(new Shelf(names.get(page), maps));
        });
        if (!ranked.isEmpty()) shelves.add(new Shelf(rankedName, List.copyOf(ranked.values())));
        if (!unlisted.isEmpty()) shelves.add(new Shelf("Other", unlisted));
        return shelves;
    }

    private static Screen page(Mode mode, Use use, List<Shelf> shelves, int at, int page) {
        Shelf shelf = shelves.get(at);
        List<String> maps = shelf.maps();
        int[] slots = maps.size() <= MAPS.length ? MAPS : maps.size() <= WIDE.length ? WIDE : FULL;
        Component title = Component.text(shelf.name() == null ? use.title() : shelf.name() + " Maps");
        Screen.Builder screen = Screen.rows(6).key(Counts.KEY).title(title).back(BACK);
        screen.pages(slots, maps.size(), page, i -> v -> map(v, mode, use, maps.get(i)),
                i -> (v, click) -> pick(v, use, maps.get(i)), to -> page(mode, use, shelves, at, to));
        String random = shelf.name() == null ? "Random Map" : "Random " + shelf.name();
        screen.press(RANDOM, v -> Entry.of(Material.FIREWORK_ROCKET).name(random).kind(use.kind())
                .line(Canvas.screenThemeFor(v).good(use.action())).render(v), (v, click) -> pick(v, use,
                shelves.size() == 1 ? null : maps.get(ThreadLocalRandom.current().nextInt(maps.size()))));
        if (shelves.size() > 1) {
            int back = Math.floorMod(at - 1, shelves.size()), next = (at + 1) % shelves.size();
            turn(screen, PREVIOUS, "Previous Page", mode, use, shelves, back);
            turn(screen, NEXT, "Next Page", mode, use, shelves, next);
        }
        return screen.build();
    }

    private static void turn(Screen.Builder screen, int slot, String label, Mode mode, Use use, List<Shelf> shelves, int to) {
        screen.press(slot, v -> Entry.of(Material.ARROW).name(label).text("To " + shelves.get(to).name() + " Maps").render(v),
                (v, click) -> {
                    Screens.swap(v, page(mode, use, shelves, to, 0));
                    return null;
                });
    }

    private static ItemStack map(Player viewer, Mode mode, Use use, String map) {
        Entry entry = Entry.of(icon(mode, map)).name(Matches.mapName(mode, map)).kind(use.kind());
        String by = picker(mode, map).getString("by");
        if (!by.isEmpty()) entry.text("by " + by);
        use.facts().accept(entry, map);
        return entry.line(Canvas.screenThemeFor(viewer).good(use.action())).render(viewer);
    }

    // the arena's own block, so a map reads at a glance; one without stays a star
    private static Material icon(Mode mode, String map) {
        var arena = mode.arena().named(map);
        String key = arena != null ? arena.meta().user().getString("icon") : "";
        Material material = key.isEmpty() ? null : Material.fromKey(key);
        return material != null ? material : Material.FIREWORK_STAR;
    }

    // where a network's map picker listed the map; empty for one it did not
    private static CompoundBinaryTag picker(Mode mode, String map) {
        var arena = mode.arena().named(map);
        if (arena == null) return CompoundBinaryTag.empty();
        for (String network : arena.meta().user().keySet()) {
            CompoundBinaryTag listed = arena.meta().user().getCompound(network).getCompound("picker");
            if (listed.keySet().contains("page")) return listed;
        }
        return CompoundBinaryTag.empty();
    }

    private static @Nullable String pick(Player viewer, Use use, @Nullable String map) {
        String refused = use.pick().apply(viewer, map);
        if (refused == null) viewer.closeInventory();
        return refused;
    }
}
