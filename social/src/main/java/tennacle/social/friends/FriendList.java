package tennacle.social.friends;

import io.github.term4.canvas.chat.ChatWidth;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.Nullable;
import tennacle.social.Whereabouts;
import tennacle.storage.Directory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static tennacle.social.Lines.red;
import static tennacle.social.Lines.rule;
import static tennacle.social.Lines.yellow;

/** The paged list as one chat message, Hypixel's shape: a centered header, eight rows, a short page padded out. */
final class FriendList {

    static final int ROWS = 8;

    private record Row(Friends.Friend friend, String name, @Nullable Player online, long lastSeen) {}

    private FriendList() {}

    /** Page {@code page} (1-based, clamped) of {@code friends}; {@code seen} has whatever the directory knows of them. */
    static Component page(List<Friends.Friend> friends, Map<UUID, Directory.Entry> seen, int page, boolean bestOnly,
                          boolean viewable, long now) {
        List<Row> online = new ArrayList<>();
        List<Row> offline = new ArrayList<>();
        for (Friends.Friend friend : friends) {
            if (bestOnly && !friend.best()) continue;
            Player player = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(friend.id());
            Directory.Entry entry = seen.get(friend.id());
            String name = player != null ? player.getUsername() : entry != null ? entry.name() : friend.name();
            Row row = new Row(friend, name, player, entry != null ? entry.lastSeen() : 0);
            (player != null ? online : offline).add(row);
        }
        Comparator<Row> byName = Comparator.comparing(row -> row.name().toLowerCase(Locale.ROOT));
        online.sort(byName);
        offline.sort(Comparator.<Row, Boolean>comparing(row -> !row.friend().best()).thenComparing(byName));
        List<Row> rows = new ArrayList<>(online);
        rows.addAll(offline);

        int pages = Math.max(1, (rows.size() + ROWS - 1) / ROWS);
        int at = Math.clamp(page, 1, pages);
        TextComponent.Builder out = Component.text().append(rule()).append(Component.newline())
                .append(header(bestOnly ? "Best Friends" : "Friends", at, pages)).append(Component.newline());
        List<Row> shown = rows.subList(Math.min(rows.size(), (at - 1) * ROWS), Math.min(rows.size(), at * ROWS));
        for (int i = 0; i < ROWS; i++) {
            if (i < shown.size()) out.append(row(shown.get(i), viewable, now));
            out.append(Component.newline());
        }
        return out.append(rule()).build();
    }

    // captured: "<< Friends (Page 2 of 74) >>", the arrows bold yellow and only where there is a page to go to
    private static Component header(String title, int page, int pages) {
        TextComponent.Builder line = Component.text();
        if (page > 1) line.append(arrow("<<", page - 1)).append(Component.text(" "));
        line.append(Component.text(title + " (Page " + page + " of " + pages + ") ", NamedTextColor.GOLD));
        if (page < pages) line.append(arrow(">>", page + 1));
        return ChatWidth.center(line.build());
    }

    private static Component arrow(String text, int page) {
        String list = "/friend list " + page;
        return Component.text(text, NamedTextColor.YELLOW, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(list))
                .hoverEvent(HoverEvent.showText(yellow("Click to view page " + page)));
    }

    private static Component row(Row row, boolean viewable, long now) {
        Component name = Component.text(row.name() + " ", NamedTextColor.GRAY)
                .decoration(TextDecoration.BOLD, row.friend().best());
        Component where = row.online() != null ? yellow(Whereabouts.of(row.online())) : red("is currently offline");
        TextComponent.Builder hover = Component.text();
        if (row.online() == null && row.lastSeen() > 0) {
            hover.append(Component.text("Last seen " + span(now - row.lastSeen()) + " ago\n", NamedTextColor.GRAY));
        }
        hover.append(Component.text("Friends for " + span(now - row.friend().since()), NamedTextColor.GRAY));
        Component line = Component.text().append(name).append(where).build();
        boolean clickable = viewable && row.online() != null;
        if (clickable) {
            hover.append(Component.text("\n\n")).append(yellow("Click here to view "))
                    .append(Component.text(row.name(), NamedTextColor.GRAY)).append(yellow("'s profile"));
            line = line.clickEvent(ClickEvent.runCommand("/friend view " + row.name()));
        }
        return line.hoverEvent(HoverEvent.showText(hover.build()));
    }

    private static final long SECOND = 1000, MINUTE = 60 * SECOND, HOUR = 60 * MINUTE, DAY = 24 * HOUR,
            MONTH = 30 * DAY, YEAR = 365 * DAY;
    private static final long[] UNITS = {YEAR, MONTH, DAY, HOUR, MINUTE, SECOND};
    private static final String[] NAMES = {"year", "month", "day", "hour", "minute", "second"};

    /** The two largest units, Hypixel's way: "a year and 4 months", "15 days", "32 minutes and 42 seconds". */
    static String span(long millis) {
        long left = Math.max(millis, SECOND);
        for (int i = 0; i < UNITS.length; i++) {
            long count = left / UNITS[i];
            if (count == 0) continue;
            String first = count(count, NAMES[i]);
            if (i + 1 == UNITS.length) return first;
            long next = (left % UNITS[i]) / UNITS[i + 1];
            return next == 0 ? first : first + " and " + count(next, NAMES[i + 1]);
        }
        return "a second";
    }

    private static String count(long count, String unit) {
        if (count == 1) return (unit.equals("hour") ? "an " : "a ") + unit;
        return count + " " + unit + "s";
    }
}
