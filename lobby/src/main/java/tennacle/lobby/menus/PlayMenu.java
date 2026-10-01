package tennacle.lobby.menus;

import io.github.term4.canvas.Canvas;
import io.github.term4.canvas.screen.Entry;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.games.KitLayouts;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.matchmaking.Play;
import tennacle.games.matchmaking.Queues;
import tennacle.lobby.Counts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A game's menu behind its NPC: its sizes in a row, smallest first, the stack the team size; under them Close and,
 * where the game has one, its layout editor. The size your side waits for glints and takes you out of the line;
 * a right click on a size picks its map ({@link MapMenu}).
 */
public final class PlayMenu {

    private record Size(Mode mode, String id, int size) {}

    private PlayMenu() {}

    public static void open(@NotNull Player viewer, @NotNull String title, @NotNull Predicate<Mode> hosts,
                            @NotNull Function<Mode, Material> icon) {
        List<Size> sizes = sizes(hosts);
        Screen.Builder screen = Screen.rows(4).key(Counts.KEY).title(Component.text(title)).close(31);
        int[] slots = Rows.centered(1, sizes.size());
        for (int i = 0; i < sizes.size(); i++) {
            Size size = sizes.get(i);
            screen.press(slots[i], v -> item(v, size, icon.apply(size.mode())), (v, click) -> {
                if (!(click instanceof Click.Right) || !MapMenu.chooses(size.mode())) return press(v, size);
                MapMenu.play(v, size.mode(), size.id());
                return null;
            });
        }
        KitLayouts.editables().stream().filter(kit -> kit.title().equals(title)).findFirst().ifPresent(kit ->
                screen.press(32, v -> Entry.of(Material.BLAZE_POWDER).name(kit.title() + " Layout Editor")
                        .text("Edit your inventory layout for " + kit.title() + ".").action("Click to edit!").render(v),
                        (v, click) -> {
                            String refused = KitLayouts.open(v, kit.id());
                            if (refused == null) v.closeInventory();
                            return refused;
                        }));
        Screens.open(viewer, screen.build());
    }

    private static List<Size> sizes(Predicate<Mode> hosts) {
        List<Size> out = new ArrayList<>();
        for (String name : Matches.modes()) {
            Mode mode = Matches.mode(name);
            if (mode == null || !hosts.test(mode)) continue;
            mode.plays().forEach((id, size) -> out.add(new Size(mode, id, size)));
        }
        out.sort(Comparator.comparingInt(Size::size));
        return out;
    }

    private static @Nullable String press(Player viewer, Size size) {
        Queues.Waiting waiting = Queues.of(viewer);
        if (waiting != null && waiting.id().equals(size.id())) {
            Queues.leave(viewer);
            return null;
        }
        String refused = Play.start(viewer, size.id());
        if (refused == null) viewer.closeInventory();
        return refused;
    }

    private static ItemStack item(Player viewer, Size size, Material icon) {
        Mode mode = size.mode();
        Entry entry = Entry.of(icon).name(Queues.label(mode, size.id())).count(size.size())
                .fact("Playing", Counts.playing(mode, size.id()));
        if (Matches.joinable(mode)) entry.fact("Waiting", Counts.waiting(mode));
        else entry.fact("Queued", Counts.queued(size.id()));
        Queues.Waiting waiting = Queues.of(viewer);
        if (waiting != null && waiting.id().equals(size.id())) {
            entry.glint(true).line(Canvas.screenThemeFor(viewer).good("You are queued (" + clock(waiting.since()) + ")"))
                    .action("Click to leave the queue!");
        } else {
            entry.action("Click to play!");
        }
        if (MapMenu.chooses(mode)) entry.action("Right-click to choose a map!");
        return entry.render(viewer);
    }

    static String clock(long since) {
        long seconds = Math.max(0, (System.currentTimeMillis() - since) / 1000);
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }
}
