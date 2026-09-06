package tennacle.audit;

import tennacle.Msg;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.canvas.screen.ClickRule;
import io.github.term4.canvas.screen.Items;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.roster.Slot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.timer.Task;
import net.minestom.server.timer.TaskSchedule;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The auditor's screens, following the live map: a tree - one node over its children, a sapling for a child
 * with children of its own and a stick for a lone shard - and a shard's players as heads. Every half second the
 * open screen re-reads its subject and repaints in place when anything moved; a subject that is gone closes it.
 * Rows one to four page the entries, the top row holds the node and its ways up and across, the bottom row turns
 * the page.
 */
public final class AuditViews {

    public static final int PAGE = 36;
    public static final int UP = 0, NODE = 4, ACROSS = 8, PREV = 45, PAPER = 49, NEXT = 53;
    private static final int FOLLOW_TICKS = 10;

    private AuditViews() {}

    /** One open screen's moving parts, read by every item and press: the entries, the page, what was painted. */
    private static final class Page<T> {
        volatile List<T> all = List.of();
        volatile int at;
        volatile String painted = "";

        @Nullable T shown(int index) {
            List<T> now = all;
            int i = at * PAGE + index;
            return i < now.size() ? now.get(i) : null;
        }

        int pages() {
            return Math.max(1, (all.size() + PAGE - 1) / PAGE);
        }
    }

    /** {@code node} over its children. */
    public static void tree(@NotNull Player viewer, @NotNull Shard node) {
        Page<Shard> page = new Page<>();
        page.all = children(node);
        Screen.Builder screen = Screen.rows(6).title(Component.text("Tree · " + Audit.handle(node))).ownClicks(ClickRule.DENY);
        Shard parent = node.parent();
        if (parent != null) {
            screen.button(UP, Items.named(Material.ARROW, Component.text("Up to " + Audit.label(parent), NamedTextColor.GRAY)),
                    (who, click) -> tree(who, parent));
        }
        screen.item(NODE, v -> shardItem(node, false));
        screen.button(ACROSS, v -> headsItem(node), (who, click) -> who(who, node));
        for (int i = 0; i < PAGE; i++) {
            int index = i;
            screen.button(9 + i, v -> {
                Shard child = page.shown(index);
                return child != null ? shardItem(child, true) : ItemStack.AIR;
            }, (who, click) -> {
                Shard child = page.shown(index);
                if (child == null) return;
                if (child.children().isEmpty() || right(click)) who(who, child);
                else tree(who, child);
            });
        }
        pager(screen, page, viewer);
        Screen built = screen.build();
        Screens.open(viewer, built);
        follow(viewer, built, () -> {
            if (!Audit.alive(node)) return null;
            page.all = children(node);
            return signature(node) + "|" + page.all.stream().map(AuditViews::signature).toList();
        }, page);
    }

    /** The players in {@code shard} as heads: a click goes to them, a right click reads their details. */
    public static void who(@NotNull Player viewer, @NotNull Shard shard) {
        Page<Player> page = new Page<>();
        page.all = Audit.playersIn(shard);
        Screen.Builder screen = Screen.rows(6).title(Component.text("Players · " + Audit.handle(shard))).ownClicks(ClickRule.DENY);
        screen.button(UP, v -> Items.named(Material.OAK_SAPLING, Component.text("The tree", NamedTextColor.GRAY)),
                (who, click) -> tree(who, shard));
        screen.item(NODE, v -> shardItem(shard, false));
        for (int i = 0; i < PAGE; i++) {
            int index = i;
            screen.button(9 + i, v -> {
                Player p = page.shown(index);
                return p != null ? headItem(p, Game.of(shard)) : ItemStack.AIR;
            }, (who, click) -> {
                Player p = page.shown(index);
                if (p == null) return;
                if (right(click)) {
                    Audit.player(who, p);
                } else {
                    who.closeInventory();
                    Audit.go(who, p);
                }
            });
        }
        pager(screen, page, viewer);
        Screen built = screen.build();
        Screens.open(viewer, built);
        follow(viewer, built, () -> {
            if (!Audit.alive(shard)) return null;
            page.all = Audit.playersIn(shard);
            return signature(shard) + "|" + page.all.stream().map(p -> p.getUsername() + Audit.color(p, Game.of(shard))).toList();
        }, page);
    }

    private static <T> void pager(Screen.Builder screen, Page<T> page, Player viewer) {
        screen.button(PREV, v -> page.at > 0
                ? Items.named(Material.ARROW, Component.text("Previous page", NamedTextColor.GRAY)) : ItemStack.AIR, (who, click) -> {
            if (page.at > 0) {
                page.at--;
                Screens.repaint(who);
            }
        });
        screen.item(PAPER, v -> page.pages() > 1
                ? Items.named(Material.PAPER, Component.text("Page " + (page.at + 1) + " of " + page.pages(), NamedTextColor.YELLOW))
                : ItemStack.AIR);
        screen.button(NEXT, v -> page.at < page.pages() - 1
                ? Items.named(Material.ARROW, Component.text("Next page", NamedTextColor.GRAY)) : ItemStack.AIR, (who, click) -> {
            if (page.at < page.pages() - 1) {
                page.at++;
                Screens.repaint(who);
            }
        });
    }

    // every half second while the screen is up: re-read the subject, repaint on a change, close on a loss
    private static void follow(Player viewer, Screen screen, Supplier<@Nullable String> reread, Page<?> page) {
        Task[] task = new Task[1];
        task[0] = MinecraftServer.getSchedulerManager().buildTask(() -> {
            if (Screens.screen(viewer) != screen) {
                task[0].cancel();
                return;
            }
            String now = reread.get();
            if (now == null) {
                task[0].cancel();
                viewer.closeInventory();
                Msg.fail(viewer, "That shard is gone.");
                return;
            }
            if (page.at >= page.pages()) page.at = page.pages() - 1;
            if (!now.equals(page.painted)) {
                page.painted = now;
                Screens.repaint(viewer);
            }
        }).repeat(TaskSchedule.tick(FOLLOW_TICKS)).schedule();
    }

    static List<Shard> children(Shard node) {
        List<Shard> out = new ArrayList<>(node.children());
        out.sort(java.util.Comparator.comparing(Audit::handle));
        return out;
    }

    // what a repaint compares: the counts an item shows
    private static String signature(Shard shard) {
        return Audit.handle(shard) + ":" + shard.members().size() + ":" + entities(shard) + ":" + shard.overlaySize()
                + ":" + shard.children().size() + ":" + Audit.owner(shard);
    }

    private static long entities(Shard shard) {
        return shard.entities().stream().filter(e -> !(e instanceof Player)).count();
    }

    /** A sapling for a tree, a stick for a lone shard, its standing in the lore. */
    public static @NotNull ItemStack shardItem(@NotNull Shard shard, boolean child) {
        boolean tree = !shard.children().isEmpty();
        Component name = Component.text(Audit.handle(shard), tree ? NamedTextColor.GREEN : NamedTextColor.WHITE);
        List<Component> lore = new ArrayList<>();
        String owner = Audit.owner(shard);
        if (owner != null) lore.add(Component.text(owner, NamedTextColor.AQUA));
        lore.add(Component.text(Audit.label(shard.instance()), NamedTextColor.GRAY));
        lore.add(Component.text(shard.members().size() + Audit.plural(shard.members().size(), " player"), NamedTextColor.GRAY));
        long entities = entities(shard);
        lore.add(Component.text(entities + Audit.plural((int) entities, " entity", " entities"), NamedTextColor.GRAY));
        lore.add(Component.text(shard.overlaySize() + Audit.plural(shard.overlaySize(), " block") + " in the diff", NamedTextColor.GRAY));
        lore.add(Component.text(shard.domain() != null ? "own clock" : "server clock", NamedTextColor.DARK_GRAY));
        if (tree) lore.add(Component.text(Audit.under(shard) + " under it", NamedTextColor.GRAY));
        if (child) {
            lore.add(Component.text(tree ? "Click: expand · Right click: players" : "Click: players", NamedTextColor.YELLOW));
        }
        return Items.lore(Items.named(tree ? Material.OAK_SAPLING : Material.STICK, name), lore);
    }

    private static ItemStack headsItem(Shard shard) {
        int n = shard.members().size();
        return Items.lore(Items.named(Material.PLAYER_HEAD, Component.text("Players here", NamedTextColor.WHITE)),
                List.of(Component.text(n + Audit.plural(n, " player"), NamedTextColor.GRAY),
                        Component.text("Click: list them", NamedTextColor.YELLOW)));
    }

    /** A player's head in their seat's color, what they are doing under it. */
    public static @NotNull ItemStack headItem(@NotNull Player p, @Nullable Game game) {
        List<Component> lore = new ArrayList<>();
        if (game != null) {
            Slot slot = game.roster().slotOf(p);
            if (slot != null) lore.add(Component.text("team " + slot.team(), Audit.color(p, game)));
            else if (game.spectators().contains(p)) lore.add(Component.text("spectating", NamedTextColor.GRAY));
            else if (game.isGhost(p)) lore.add(Component.text("ghost", NamedTextColor.GRAY));
        }
        lore.add(Component.text(Audit.client(p), NamedTextColor.GRAY));
        lore.add(Component.text("Click: go there · Right click: details", NamedTextColor.YELLOW));
        return Items.lore(Items.head(p.getSkin(), Component.text(p.getUsername(), Audit.color(p, game))), lore);
    }

    private static boolean right(Click click) {
        return click instanceof Click.Right;
    }
}
