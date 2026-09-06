package tennacle.games.watch;

import tennacle.Msg;

import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.Replay;
import io.github.term4.canvas.screen.ClickRule;
import io.github.term4.canvas.screen.Items;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventListener;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.item.ItemDropEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerHandAnimationEvent;
import net.minestom.server.event.player.PlayerSwapItemEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The replay's remote: the hotbar whoever leads the theater holds, and the timeline every watcher reads off the
 * experience bar and the line above the hotbar. The players' compass, slower and faster on arrow heads whose
 * left click picks the step and right click takes it, a seek head each way on the same pattern, the play/pause
 * dye, and the star that opens the replay menu (share, bookmarks, play from here). The hotbar is theirs to press
 * and nothing more: no drop, no move, no swap.
 */
public final class Remote {

    /** The hotbar slots, left to right. */
    public static final int PLAYERS = 0, SLOWER = 2, BACK = 3, PLAY = 4, FORWARD = 5, FASTER = 6, MENU = 8;
    static final double MIN_RATE = 0.05, MAX_RATE = 16, HALVING_FLOOR = 0.25, DOUBLING_CEILING = 8;
    /** Rate steps a speed head cycles through; {@code 0} halves or doubles. */
    static final double[] RATE_STEPS = {0, 1, 0.5, 0.1, 0.05};
    /** Seek steps in seconds. */
    static final int[] SEEK_STEPS = {1, 5, 30, 60, 300};

    // minecraft-heads.com's arrow fonts, hosted by Mojang like any skin
    private static final String BLUE_UP = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjJmYzIzODY2NTIzY2FhYThhOTUzNDU2NjEyN2E2ZjgzODlhZjNlNzZiOGUzYzMzYzI0NzNjYmE2ODg5YzQifX19";
    private static final String BLUE_DOWN = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjBkMWRmODA0NmYwYjVkOTM0YzNlMDU3OThlYWNmZWVhNmQ3YjU5NWRiZTI2ZGViZjdkYjlhY2M4YzRmYTc5OCJ9fX0=";
    private static final String WHITE_LEFT = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYTE4NWM5N2RiYjgzNTNkZTY1MjY5OGQyNGI2NDMyN2I3OTNhM2YzMmE5OGJlNjdiNzE5ZmJlZGFiMzVlIn19fQ==";
    private static final String WHITE_RIGHT = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzFjMGVkZWRkNzExNWZjMWIyM2Q1MWNlOTY2MzU4YjI3MTk1ZGFmMjZlYmI2ZTQ1YTY2YzM0YzY5YzM0MDkxIn19fQ==";

    private static final Map<Player, Remote> BY_PLAYER = new ConcurrentHashMap<>();
    private static boolean installed;

    /** A controller's picks: the index into {@link #RATE_STEPS} for each speed head, into {@link #SEEK_STEPS} for the seeks. */
    private static final class Controls {
        int slower, faster, seek;
    }

    private final Playback playback;
    private final String gameId;
    private final Supplier<List<Lineup.Entry>> alive;
    private final Set<Player> watchers = ConcurrentHashMap.newKeySet();
    private final Map<Player, Controls> controllers = new ConcurrentHashMap<>();
    private final Map<Player, Long> pressed = new ConcurrentHashMap<>(); // a right click's tick: the swing after it is the same press
    private volatile boolean shownPaused;

    Remote(@NotNull Playback playback, @NotNull String gameId, @NotNull Supplier<List<Lineup.Entry>> alive) {
        this.playback = playback;
        this.gameId = gameId;
        this.alive = alive;
    }

    /** The one set of listeners; every remote routes through {@link #BY_PLAYER}. */
    static void install() {
        if (installed) return;
        installed = true;
        var events = MinecraftServer.getGlobalEventHandler();
        // cancelled or not: a spectator policy elsewhere may have refused the use already
        events.addListener(EventListener.builder(PlayerUseItemEvent.class).ignoreCancelled(false).handler(e -> {
            Remote remote = BY_PLAYER.get(e.getPlayer());
            if (remote == null) return;
            e.setCancelled(true);
            remote.press(e.getPlayer(), true);
        }).build());
        events.addListener(EventListener.builder(PlayerBlockInteractEvent.class).ignoreCancelled(false).handler(e -> {
            Remote remote = BY_PLAYER.get(e.getPlayer());
            if (remote == null) return;
            e.setCancelled(true);
            e.setBlockingItemUse(true);
            remote.press(e.getPlayer(), true);
        }).build());
        events.addListener(EventListener.builder(PlayerHandAnimationEvent.class).ignoreCancelled(false).handler(e -> {
            Remote remote = BY_PLAYER.get(e.getPlayer());
            if (remote != null) remote.press(e.getPlayer(), false);
        }).build());
        events.addListener(InventoryPreClickEvent.class, e -> {
            if (BY_PLAYER.containsKey(e.getPlayer()) && Screens.screen(e.getPlayer()) == null) e.setCancelled(true);
        });
        events.addListener(ItemDropEvent.class, e -> {
            if (BY_PLAYER.containsKey(e.getPlayer())) e.setCancelled(true);
        });
        events.addListener(PlayerSwapItemEvent.class, e -> {
            if (BY_PLAYER.containsKey(e.getPlayer())) e.setCancelled(true);
        });
    }

    /** The remote {@code player} holds or watches through, or null. */
    public static @Nullable Remote of(@NotNull Player player) {
        return BY_PLAYER.get(player);
    }

    void enter(@NotNull Player viewer, boolean controller) {
        watchers.add(viewer);
        BY_PLAYER.put(viewer, this);
        viewer.getInventory().clear();
        viewer.setLevel(0);
        viewer.setExp(0f);
        if (controller) {
            controllers.put(viewer, new Controls());
            deal(viewer);
        }
        timeline(viewer);
    }

    void exit(@NotNull Player viewer) {
        if (!watchers.remove(viewer)) return;
        controllers.remove(viewer);
        pressed.remove(viewer);
        BY_PLAYER.remove(viewer, this);
        viewer.getInventory().clear(); // the lobby dresses the arrival
        viewer.setLevel(0);
        viewer.setExp(0f);
        viewer.sendActionBar(Component.empty());
    }

    void close() {
        for (Player viewer : List.copyOf(watchers)) exit(viewer);
    }

    /** Every watcher's bar and line, and the dye once the playing state moved. */
    void tick() {
        for (Player viewer : watchers) timeline(viewer);
        boolean paused = playback.paused();
        if (paused != shownPaused) {
            shownPaused = paused;
            for (Player viewer : controllers.keySet()) viewer.getInventory().setItemStack(PLAY, playItem());
        }
    }

    private void timeline(Player viewer) {
        long length = Math.max(1, playback.length());
        long at = Math.max(0, Math.min(length, playback.tick()));
        viewer.setExp((float) at / length);
        viewer.sendActionBar(Component.text()
                .append(Component.text(playback.paused() ? "‣ Paused" : "‣ Playing",
                        playback.paused() ? NamedTextColor.RED : NamedTextColor.GREEN))
                .append(Component.text("   " + stamp(at) + " / " + stamp(length), NamedTextColor.YELLOW))
                .append(Component.text("   " + rateText(playback.rate()) + "x", NamedTextColor.GOLD))
                .build());
    }

    private void deal(Player viewer) {
        Controls controls = controllers.get(viewer);
        var inv = viewer.getInventory();
        inv.setItemStack(PLAYERS, Lineup.compass());
        inv.setItemStack(SLOWER, Items.head(BLUE_DOWN, Component.text("Slower: " + rateStep(RATE_STEPS[controls.slower], false), NamedTextColor.AQUA)));
        inv.setItemStack(BACK, Items.head(WHITE_LEFT, Component.text("Back " + seekStep(SEEK_STEPS[controls.seek]), NamedTextColor.WHITE)));
        inv.setItemStack(PLAY, playItem());
        inv.setItemStack(FORWARD, Items.head(WHITE_RIGHT, Component.text("Forward " + seekStep(SEEK_STEPS[controls.seek]), NamedTextColor.WHITE)));
        inv.setItemStack(FASTER, Items.head(BLUE_UP, Component.text("Faster: " + rateStep(RATE_STEPS[controls.faster], true), NamedTextColor.AQUA)));
        inv.setItemStack(MENU, Items.named(Material.NETHER_STAR, Component.text("Replay menu", NamedTextColor.LIGHT_PURPLE)));
    }

    private ItemStack playItem() {
        return playback.paused()
                ? Items.named(Material.GRAY_DYE, Component.text("Play", NamedTextColor.GRAY))
                : Items.named(Material.LIME_DYE, Component.text("Pause", NamedTextColor.GREEN));
    }

    // a right click and the swing the client sends after it are one press
    private void press(Player viewer, boolean right) {
        Controls controls = controllers.get(viewer);
        if (controls == null) return;
        long now = playback.shard().ticks();
        if (right) pressed.put(viewer, now);
        else if (pressed.getOrDefault(viewer, -1L) == now) return;
        switch (viewer.getHeldSlot()) {
            case PLAYERS -> { if (right) players(viewer); }
            case SLOWER -> {
                if (right) rate(slower(playback.rate(), RATE_STEPS[controls.slower]));
                else controls.slower = (controls.slower + 1) % RATE_STEPS.length;
                deal(viewer);
            }
            case FASTER -> {
                if (right) rate(faster(playback.rate(), RATE_STEPS[controls.faster]));
                else controls.faster = (controls.faster + 1) % RATE_STEPS.length;
                deal(viewer);
            }
            case BACK, FORWARD -> {
                int step = SEEK_STEPS[controls.seek] * 20;
                if (right) seek(viewer.getHeldSlot() == BACK ? playback.tick() - step : playback.tick() + step);
                else controls.seek = (controls.seek + 1) % SEEK_STEPS.length;
                deal(viewer);
            }
            case PLAY -> playback.pause(!playback.paused());
            case MENU -> { if (right) menu(viewer); }
            default -> { }
        }
    }

    // the lib's rate call unpauses; a paused replay stays paused at its new speed
    private void rate(double rate) {
        boolean paused = playback.paused();
        playback.rate(rate);
        if (paused) playback.pause(true);
    }

    private void seek(long target) {
        playback.seek(Math.max(0, Math.min(playback.length(), target)));
    }

    /** The rate after a slower press: halving stops at {@link #HALVING_FLOOR}; a fixed step never goes under {@link #MIN_RATE}. */
    static double slower(double rate, double step) {
        if (step == 0) return rate > HALVING_FLOOR ? Math.max(HALVING_FLOOR, rate / 2) : rate;
        return Math.max(MIN_RATE, rate - step);
    }

    /** The rate after a faster press: doubling stops at {@link #DOUBLING_CEILING}; a fixed step never tops {@link #MAX_RATE}. */
    static double faster(double rate, double step) {
        if (step == 0) return rate < DOUBLING_CEILING ? Math.min(DOUBLING_CEILING, rate * 2) : rate;
        return Math.min(MAX_RATE, rate + step);
    }

    /** The players alive at the cursor, as heads; a click lands {@code viewer} at one. */
    public void players(@NotNull Player viewer) {
        Lineup.open(viewer, alive.get());
    }

    /** The replay menu: share, bookmarks, and play from here (not in yet). */
    public void menu(@NotNull Player viewer) {
        Screen screen = Screen.rows(3).title(Component.text("Replay")).ownClicks(ClickRule.DENY)
                .button(11, Items.named(Material.PAPER, Component.text("Share", NamedTextColor.GREEN)), (who, click) -> {
                    who.closeInventory();
                    who.sendMessage(Component.text("Click here to get a command to share with your friends!",
                            NamedTextColor.AQUA, TextDecoration.BOLD)
                            .clickEvent(ClickEvent.copyToClipboard("/replay watch " + gameId)));
                })
                .button(13, Items.named(Material.BOOK, Component.text("Bookmarks", NamedTextColor.GOLD)), (who, click) -> bookmarks(who))
                .item(15, Items.lore(Items.named(Material.CLOCK, Component.text("Play from here", NamedTextColor.YELLOW)),
                        List.of(Component.text("Not in yet.", NamedTextColor.GRAY))))
                .build();
        Screens.open(viewer, screen);
    }

    /** The replay's bookmarks as a screen; a click seeks to one. */
    public void bookmarks(@NotNull Player viewer) {
        List<Replay.Bookmark> marks = playback.bookmarks();
        if (marks.isEmpty()) {
            viewer.closeInventory();
            Msg.say(viewer, "This replay has no bookmarks.");
            return;
        }
        int rows = Math.min(6, (marks.size() + 8) / 9);
        Screen.Builder screen = Screen.rows(rows).title(Component.text("Bookmarks")).ownClicks(ClickRule.DENY);
        for (int i = 0; i < Math.min(marks.size(), rows * 9); i++) {
            Replay.Bookmark mark = marks.get(i);
            screen.button(i, markItem(mark), (who, click) -> {
                seek(mark.tick());
                who.closeInventory();
            });
        }
        Screens.open(viewer, screen.build());
    }

    // a goal reads as its scorer in the side's color; anything else by its name
    private static ItemStack markItem(Replay.Bookmark mark) {
        CompoundBinaryTag data = mark.data();
        String at = stamp(mark.tick());
        if (mark.name().equals("goal")) {
            TextColor color = data.keySet().contains("color") ? TextColor.color(data.getInt("color")) : NamedTextColor.WHITE;
            return Items.named(Material.PAPER, Component.text(data.getString("scorer") + " scored", color)
                    .append(Component.text("  " + at, NamedTextColor.GRAY)));
        }
        return Items.named(Material.PAPER, Component.text(mark.name(), NamedTextColor.WHITE)
                .append(Component.text("  " + at, NamedTextColor.GRAY)));
    }

    static String rateStep(double step, boolean up) {
        if (step == 0) return up ? "double" : "halve";
        return (up ? "+" : "-") + rateText(step) + "x";
    }

    static String seekStep(int seconds) {
        return seconds >= 60 ? seconds / 60 + "m" : seconds + "s";
    }

    /** {@code 8.0}, {@code 0.5}, {@code 0.25}, {@code 0.05}: two decimals with one trailing zero dropped. */
    static String rateText(double rate) {
        String text = String.format("%.2f", rate);
        return text.endsWith("0") ? text.substring(0, text.length() - 1) : text;
    }

    static String stamp(long ticks) {
        long seconds = Math.max(0, ticks) / 20;
        return "%02d:%02d".formatted(seconds / 60, seconds % 60);
    }
}
