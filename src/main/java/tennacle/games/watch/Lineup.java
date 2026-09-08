package tennacle.games.watch;

import net.minestom.server.MinecraftServer;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.network.packet.server.play.SpawnPositionPacket;
import net.minestom.server.network.packet.server.play.data.WorldPos;
import net.minestom.server.timer.TaskSchedule;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tennacle.Msg;

import io.github.term4.canvas.screen.ClickRule;
import io.github.term4.canvas.screen.Items;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerSkin;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * The players alive right now, as heads on a screen; a click lands the viewer at one, and the compass then
 * points at them ({@link #aim}) as they move, until the next pick. A replay reads its ghosts, a live game its
 * seated members - each hands over a {@link Supplier} of {@link Entry entries}, read at the moment the compass
 * is used.
 */
public final class Lineup {

    /** One alive player: the name in the side's color, their skin, and where they stand. */
    public record Entry(@NotNull String name, @NotNull TextColor color, @Nullable PlayerSkin skin,
                        @NotNull Supplier<@Nullable Pos> at) {}

    private static final Component NAME = Component.text("Players", NamedTextColor.AQUA);
    /** A screen's own title: no color, so it draws in the gray a container's label always has. */
    private static final Component TITLE = Component.text("Players");
    private static final int PAGE = 36; // rows two to five; the top row breathes, the bottom row turns the page
    private static final int PREV = 45, PAPER = 49, NEXT = 53;

    private record Aim(@Nullable String who, Supplier<@Nullable Pos> at, @Nullable Point sent) {}

    private static final Map<Player, Aim> AIMS = new ConcurrentHashMap<>();
    private static boolean installed;

    private Lineup() {}

    /** The tick that keeps every aimed compass on its player: the world's compass position is the target's block. */
    public static void install() {
        if (installed) return;
        installed = true;
        // a view ends at unaim, but a disconnect never passes through one: Grotto's own handler unspectates,
        // which is its hooks, not this app's exit. Without this the map keeps the Player, and the tick below
        // walks it for the life of the server
        MinecraftServer.getGlobalEventHandler().addListener(PlayerDisconnectEvent.class,
                e -> AIMS.remove(e.getPlayer()));
        MinecraftServer.getSchedulerManager().buildTask(() -> AIMS.forEach((viewer, aim) -> {
            if (!viewer.isOnline()) { // a body removed without a disconnect, in a test or a teardown
                AIMS.remove(viewer);
                return;
            }
            if (viewer.getInstance() == null) return; // mid-transfer: the aim waits for them to land
            Pos at = aim.at().get();
            if (at == null) return;
            Point block = new BlockVec(at.blockX(), at.blockY(), at.blockZ());
            if (block.equals(aim.sent())) return;
            AIMS.put(viewer, new Aim(aim.who(), aim.at(), block));
            viewer.sendPacket(new SpawnPositionPacket(new WorldPos(viewer.getInstance().getDimensionName(), block), 0f, 0f));
        })).repeat(TaskSchedule.tick(1)).schedule();
    }

    /** Points {@code viewer}'s compass at whatever {@code at} says, tick by tick, until {@link #unaim}. */
    public static void aim(@NotNull Player viewer, @NotNull Supplier<@Nullable Pos> at) {
        aim(viewer, null, at);
    }

    /** {@link #aim} naming whom it follows, so a watcher's landings can read their side. */
    public static void aim(@NotNull Player viewer, @Nullable String who, @NotNull Supplier<@Nullable Pos> at) {
        AIMS.put(viewer, new Aim(who, at, null));
    }

    /** Where {@code viewer}'s compass points, or null - what a spectator of a spectator inherits. */
    public static @Nullable Supplier<@Nullable Pos> aimOf(@NotNull Player viewer) {
        Aim aim = AIMS.get(viewer);
        return aim != null ? aim.at() : null;
    }

    /** Whom {@code viewer}'s compass follows, or null; a watcher of a watcher inherits the name with the aim. */
    public static @Nullable String following(@NotNull Player viewer) {
        Aim aim = AIMS.get(viewer);
        return aim != null ? aim.who() : null;
    }

    /** The compass back on the world's own spawn. */
    public static void unaim(@NotNull Player viewer) {
        if (AIMS.remove(viewer) == null || !viewer.isOnline() || viewer.getInstance() == null) return;
        Pos home = viewer.getRespawnPoint();
        viewer.sendPacket(new SpawnPositionPacket(new WorldPos(viewer.getInstance().getDimensionName(), home), home.yaw(), home.pitch()));
    }

    /** The compass the picker opens from. */
    public static @NotNull ItemStack compass() {
        return Items.named(Material.COMPASS, NAME);
    }

    /** Opens the picker on {@code entries}; nobody alive says so instead. */
    public static void open(@NotNull Player viewer, @NotNull List<Entry> entries) {
        open(viewer, entries, 0);
    }

    private static void open(Player viewer, List<Entry> entries, int page) {
        if (entries.isEmpty()) {
            Msg.say(viewer, "Nobody is alive right now.");
            return;
        }
        int pages = (entries.size() + PAGE - 1) / PAGE;
        int at = Math.max(0, Math.min(pages - 1, page));
        Screen.Builder screen = Screen.rows(6).title(TITLE).ownClicks(ClickRule.DENY);
        for (int i = at * PAGE; i < Math.min(entries.size(), (at + 1) * PAGE); i++) {
            Entry entry = entries.get(i);
            screen.button(9 + i - at * PAGE, Items.head(entry.skin(), Component.text(entry.name(), entry.color())),
                    (who, click) -> {
                        Pos to = entry.at().get();
                        who.closeInventory();
                        if (to == null) {
                            Msg.fail(who, Msg.v(entry.name()), " is gone.");
                            return;
                        }
                        who.teleport(to);
                        aim(who, entry.name(), entry.at());
                    });
        }
        if (pages > 1) {
            screen.item(PAPER, Items.named(Material.PAPER,
                    Component.text("Page " + (at + 1) + " of " + pages, NamedTextColor.YELLOW)));
            if (at > 0) {
                screen.button(PREV, Items.named(Material.ARROW, Component.text("Previous page", NamedTextColor.GRAY)),
                        (who, click) -> open(who, entries, at - 1));
            }
            if (at < pages - 1) {
                screen.button(NEXT, Items.named(Material.ARROW, Component.text("Next page", NamedTextColor.GRAY)),
                        (who, click) -> open(who, entries, at + 1));
            }
        }
        Screens.open(viewer, screen.build());
    }
}
