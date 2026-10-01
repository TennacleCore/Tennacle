package tennacle.lobby.menus;

import io.github.term4.canvas.Canvas;
import io.github.term4.canvas.screen.Entry;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
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

import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * A mode's maps, from a right click: on a size in {@link PlayMenu} a map plays there, on a game in the duel picker
 * ({@link PlayerMenu}) the request names it; Random Map leaves it to the pool. Laid out as Hypixel's map selector:
 * seven a row over three rows, Random Map under them, Go Back at the bottom.
 */
final class MapMenu {

    /** {@code pick} gets a null map for Random Map, and answers a refusal or null once it went through. */
    record Use(String title, String kind, String action, BiConsumer<Entry, String> facts,
               BiFunction<Player, String, String> pick) {}

    private static final int[] MAPS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private static final int RANDOM = 40, BACK = 49;

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
        Screens.push(viewer, page(mode, use, 0));
    }

    private static Screen page(Mode mode, Use use, int page) {
        List<String> maps = mode.arena().names().stream()
                .sorted(Comparator.comparing(map -> Matches.mapName(mode, map))).toList();
        Screen.Builder screen = Screen.rows(6).key(Counts.KEY).title(Component.text(use.title())).back(BACK);
        screen.pages(MAPS, maps.size(), page, i -> v -> map(v, mode, use, maps.get(i)),
                i -> (v, click) -> pick(v, use, maps.get(i)), to -> page(mode, use, to));
        screen.press(RANDOM, v -> Entry.of(Material.FIREWORK_ROCKET).name("Random Map").kind(use.kind())
                .line(Canvas.screenThemeFor(v).good(use.action())).render(v), (v, click) -> pick(v, use, null));
        return screen.build();
    }

    private static ItemStack map(Player viewer, Mode mode, Use use, String map) {
        Entry entry = Entry.of(Material.FIREWORK_STAR).name(Matches.mapName(mode, map)).kind(use.kind());
        use.facts().accept(entry, map);
        return entry.line(Canvas.screenThemeFor(viewer).good(use.action())).render(viewer);
    }

    private static @Nullable String pick(Player viewer, Use use, @Nullable String map) {
        String refused = use.pick().apply(viewer, map);
        if (refused == null) viewer.closeInventory();
        return refused;
    }
}
