package tennacle.lobby.menus;

import io.github.term4.canvas.Canvas;
import io.github.term4.canvas.screen.Entry;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.grotto.modules.flow.Countdown;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A game's menu behind its NPC: a centered row per kind of game (the modes under one banner), its sizes smallest
 * first, the stack the team size. The size a side waits for glints and takes it out of the line.
 */
public final class PlayMenu {

    private record Size(Mode mode, String id, int size) {}

    private PlayMenu() {}

    public static void open(@NotNull Player viewer, @NotNull String title, @NotNull Predicate<Mode> hosts,
                            @NotNull Function<Mode, Material> icon) {
        List<List<Size>> rows = rows(hosts);
        int chest = rows.size() <= 1 ? 3 : Math.min(6, rows.size() + 2);
        Screen.Builder screen = Screen.rows(chest).key(Counts.KEY).title(Component.text(title));
        for (int r = 0; r < rows.size() && r < chest - 1; r++) {
            List<Size> row = rows.get(r);
            int[] slots = Rows.centered(r + 1, row.size());
            for (int i = 0; i < row.size(); i++) {
                Size size = row.get(i);
                screen.press(slots[i], v -> item(v, size, icon.apply(size.mode())), (v, click) -> press(v, size));
            }
        }
        Screens.open(viewer, screen.build());
    }

    /** Whether {@code hosts} leaves exactly one size to play, which an NPC starts without a menu. */
    public static @Nullable String only(@NotNull Predicate<Mode> hosts) {
        List<List<Size>> rows = rows(hosts);
        return rows.size() == 1 && rows.getFirst().size() == 1 ? rows.getFirst().getFirst().id() : null;
    }

    private static List<List<Size>> rows(Predicate<Mode> hosts) {
        Map<String, List<Size>> byBanner = new LinkedHashMap<>();
        for (String name : Matches.modes()) {
            Mode mode = Matches.mode(name);
            if (mode == null || !hosts.test(mode)) continue;
            List<Size> row = byBanner.computeIfAbsent(mode.banner(), banner -> new ArrayList<>());
            mode.plays().forEach((id, size) -> row.add(new Size(mode, id, size)));
        }
        List<List<Size>> rows = new ArrayList<>();
        for (List<Size> row : byBanner.values()) {
            row.sort(Comparator.comparingInt(Size::size));
            rows.add(row);
        }
        return rows;
    }

    private static @Nullable String press(Player viewer, Size size) {
        Queues.Waiting waiting = Queues.of(viewer);
        if (waiting != null && waiting.id().equals(size.id())) {
            Queues.leave(viewer);
            return null;
        }
        String refused = Play.start(viewer, size.id());
        if (refused == null && Matches.joinable(size.mode())) viewer.closeInventory(); // off to the waiting room
        return refused;
    }

    private static ItemStack item(Player viewer, Size size, Material icon) {
        Mode mode = size.mode();
        Entry entry = Entry.of(icon).name(Queues.label(mode, size.id())).kind(shape(mode, size.size()))
                .fact("Playing", Counts.playing(mode, size.id())).count(size.size());
        if (Matches.joinable(mode)) entry.fact("Waiting", Counts.waiting(mode));
        else entry.fact("In queue", Counts.queued(size.id()));
        Queues.Waiting waiting = Queues.of(viewer);
        if (waiting != null && waiting.id().equals(size.id())) {
            entry.glint(true).line(Canvas.themeFor(viewer).good("You're queued · " + clock(waiting.since())))
                    .action("Click to leave the queue");
        } else {
            entry.action("Click to play");
        }
        return entry.render(viewer);
    }

    private static String shape(Mode mode, int size) {
        int teams = teams(mode, size);
        if (teams == 2) return size == 1 ? "One on one" : "Two teams of " + size;
        return size == 1 ? teams + " players" : teams + " teams of " + size;
    }

    // a room that fills starts one seat short of every team full
    private static int teams(Mode mode, int size) {
        Object startAt = mode.type().rules().get(Countdown.START_AT);
        return startAt instanceof Integer at && at > 0 ? (at + 1) / size : 2;
    }

    static String clock(long since) {
        long seconds = Math.max(0, (System.currentTimeMillis() - since) / 1000);
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }
}
