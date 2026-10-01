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

/**
 * One size's maps, from a right click on it in {@link PlayMenu}: a map plays on that map, Random Map on any.
 * Laid out as Hypixel's map selector: seven a row over three rows, Random Map under them, Go Back at the bottom.
 */
final class MapMenu {

    private static final int[] MAPS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private static final int RANDOM = 40, BACK = 49;

    private MapMenu() {}

    static void open(@NotNull Player viewer, @NotNull Mode mode, @NotNull String id) {
        Screens.push(viewer, page(mode, id, 0));
    }

    private static Screen page(Mode mode, String id, int page) {
        List<String> maps = mode.arena().names().stream()
                .sorted(Comparator.comparing(map -> Matches.mapName(mode, map))).toList();
        String label = Queues.label(mode, id);
        Screen.Builder screen = Screen.rows(6).key(Counts.KEY).title(Component.text(label)).back(BACK);
        screen.pages(MAPS, maps.size(), page, i -> v -> map(v, mode, id, maps.get(i)),
                i -> (v, click) -> play(v, id, maps.get(i)), to -> page(mode, id, to));
        screen.press(RANDOM, v -> Entry.of(Material.FIREWORK_ROCKET).name("Random Map").kind(label)
                .line(Canvas.screenThemeFor(v).good("► Click to Play")).render(v), (v, click) -> play(v, id, null));
        return screen.build();
    }

    private static ItemStack map(Player viewer, Mode mode, String id, String map) {
        Entry entry = Entry.of(Material.FIREWORK_STAR).name(Matches.mapName(mode, map)).kind(Queues.label(mode, id));
        if (Matches.joinable(mode)) entry.fact("Available Games", Matches.waitingOn(mode, map));
        else entry.fact("Queued", Queues.queued(id, map));
        return entry.line(Canvas.screenThemeFor(viewer).good("► Click to Play")).render(viewer);
    }

    private static @Nullable String play(Player viewer, String id, @Nullable String map) {
        String refused = Play.start(viewer, id, map);
        if (refused == null) viewer.closeInventory();
        return refused;
    }
}
