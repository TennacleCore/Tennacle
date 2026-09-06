package tennacle.games.watch;

import tennacle.Msg;
import tennacle.commands.AppCommand;
import tennacle.commands.Commands;

import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.Replay;
import io.github.term4.canvas.screen.ClickRule;
import io.github.term4.canvas.screen.Items;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.arguments.ArgumentType;
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
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The replay's remote: the hotbar whoever leads the theater holds, and the timeline every watcher reads off the
 * experience bar and the line above the hotbar. The players' compass, slower and faster on arrow heads that walk
 * the {@link #LADDER} a rung at a time, a seek head each way sharing one step (left click picks it, right click
 * takes it), the play/pause dye, and the star that opens the replay menu (share, bookmarks, play from here).
 * {@code /rate} sets any speed between {@link #MIN_RATE} and {@link #MAX_RATE}; the heads then snap back onto the
 * ladder. The hotbar is theirs to press and nothing more: no drop, no move, no swap.
 */
public final class Remote {

    /** The hotbar slots, left to right. */
    public static final int PLAYERS = 0, SLOWER = 2, BACK = 3, PLAY = 4, FORWARD = 5, FASTER = 6, MENU = 8;
    /** What {@code /rate} allows. */
    public static final double MIN_RATE = 0.01, MAX_RATE = 10;
    /** The speeds the heads walk. */
    static final double[] LADDER = {0.25, 0.5, 1, 2, 4, 8};
    /** Seek steps in seconds, shared by both seek heads. */
    static final int[] SEEK_STEPS = {1, 5, 30, 60, 300};

    // minecraft-heads.com's arrow fonts, hosted by Mojang like any skin
    private static final String BLUE_UP = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjJmYzIzODY2NTIzY2FhYThhOTUzNDU2NjEyN2E2ZjgzODlhZjNlNzZiOGUzYzMzYzI0NzNjYmE2ODg5YzQifX19";
    private static final String BLUE_DOWN = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjBkMWRmODA0NmYwYjVkOTM0YzNlMDU3OThlYWNmZWVhNmQ3YjU5NWRiZTI2ZGViZjdkYjlhY2M4YzRmYTc5OCJ9fX0=";
    private static final String WHITE_LEFT = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYTE4NWM5N2RiYjgzNTNkZTY1MjY5OGQyNGI2NDMyN2I3OTNhM2YzMmE5OGJlNjdiNzE5ZmJlZGFiMzVlIn19fQ==";
    private static final String WHITE_RIGHT = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzFjMGVkZWRkNzExNWZjMWIyM2Q1MWNlOTY2MzU4YjI3MTk1ZGFmMjZlYmI2ZTQ1YTY2YzM0YzY5YzM0MDkxIn19fQ==";

    private static final Map<Player, Remote> BY_PLAYER = new ConcurrentHashMap<>();
    private static boolean installed;

    /** A controller's pick: the index into {@link #SEEK_STEPS} both seek heads read. */
    private static final class Controls {
        int seek;
    }

    private final Playback playback;
    private final String gameId;
    private final Supplier<List<Lineup.Entry>> alive;
    private final Function<Replay.Bookmark, ItemStack> marks;
    private final Set<Player> watchers = ConcurrentHashMap.newKeySet();
    private final Map<Player, Controls> controllers = new ConcurrentHashMap<>();
    private final Map<Player, Long> pressed = new ConcurrentHashMap<>(); // a right click's tick: the swing after it is the same press
    private volatile boolean shownPaused;

    /** {@code marks} renders a bookmark as the mode reads it; {@link #plainMark} for a mode with no say. */
    Remote(@NotNull Playback playback, @NotNull String gameId, @NotNull Supplier<List<Lineup.Entry>> alive,
           @NotNull Function<Replay.Bookmark, ItemStack> marks) {
        this.playback = playback;
        this.gameId = gameId;
        this.alive = alive;
        this.marks = marks;
    }

    /** The one set of listeners and the {@code /rate} command; every remote routes through {@link #BY_PLAYER}. */
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

        AppCommand rate = Commands.player("rate", "Set the speed of the replay you're watching.")
                .usage("/rate <speed>", "any speed from " + rateText(MIN_RATE) + "x to " + rateText(MAX_RATE) + "x");
        var speed = ArgumentType.Double("speed");
        rate.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Remote remote = BY_PLAYER.get(p);
            if (remote == null) {
                Msg.fail(p, "You're not watching a replay.");
                return;
            }
            if (!remote.controllers.containsKey(p)) {
                Msg.fail(p, "Whoever started the watch holds the remote.");
                return;
            }
            double asked = Math.max(MIN_RATE, Math.min(MAX_RATE, ctx.get(speed)));
            remote.rate(asked);
            Msg.ok(p, "Replay speed: ", Msg.v(rateText(asked) + "x"), ".");
        }, speed);
        Commands.register(rate);
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
        String seek = seekStep(SEEK_STEPS[controls.seek]);
        var inv = viewer.getInventory();
        inv.setItemStack(PLAYERS, Lineup.compass());
        inv.setItemStack(SLOWER, Items.head(BLUE_DOWN, Component.text("Slower ×2", NamedTextColor.AQUA)));
        inv.setItemStack(BACK, Items.head(WHITE_LEFT, Component.text("Back " + seek, NamedTextColor.WHITE)));
        inv.setItemStack(PLAY, playItem());
        inv.setItemStack(FORWARD, Items.head(WHITE_RIGHT, Component.text("Forward " + seek, NamedTextColor.WHITE)));
        inv.setItemStack(FASTER, Items.head(BLUE_UP, Component.text("Faster ×2", NamedTextColor.AQUA)));
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
            case SLOWER -> { if (right) rate(slower(playback.rate())); }
            case FASTER -> { if (right) rate(faster(playback.rate())); }
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

    /** The next rung of the {@link #LADDER} under {@code rate}, or {@code rate} itself below the lowest. */
    static double slower(double rate) {
        for (int i = LADDER.length - 1; i >= 0; i--) {
            if (LADDER[i] < rate - 1.0e-9) return LADDER[i];
        }
        return rate;
    }

    /** The next rung of the {@link #LADDER} over {@code rate}, or {@code rate} itself past the top. */
    static double faster(double rate) {
        for (double rung : LADDER) {
            if (rung > rate + 1.0e-9) return rung;
        }
        return rate;
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
                .item(15, Items.lore(Items.named(Material.ARROW, Component.text("Play from here", NamedTextColor.YELLOW)),
                        List.of(Component.text("Not in yet.", NamedTextColor.GRAY))))
                .build();
        Screens.open(viewer, screen);
    }

    /** The replay's bookmarks as the mode renders them, from the second row down; a click seeks to one. */
    public void bookmarks(@NotNull Player viewer) {
        List<Replay.Bookmark> marks = playback.bookmarks();
        if (marks.isEmpty()) {
            viewer.closeInventory();
            Msg.say(viewer, "This replay has no bookmarks.");
            return;
        }
        int rows = Math.min(6, 2 + (marks.size() + 8) / 9);
        Screen.Builder screen = Screen.rows(rows).title(Component.text("Bookmarks")).ownClicks(ClickRule.DENY);
        for (int i = 0; i < Math.min(marks.size(), (rows - 2) * 9); i++) {
            Replay.Bookmark mark = marks.get(i);
            screen.button(9 + i, this.marks.apply(mark), (who, click) -> {
                seek(mark.tick());
                who.closeInventory();
            });
        }
        Screens.open(viewer, screen.build());
    }

    /** A bookmark as a mode with no renderer of its own reads: its name on paper, the moment underneath. */
    public static @NotNull ItemStack plainMark(@NotNull Replay.Bookmark mark) {
        return Items.lore(Items.named(Material.PAPER, Component.text(mark.name(), NamedTextColor.WHITE)),
                List.of(Component.text("At " + stamp(mark.tick()), NamedTextColor.GRAY)));
    }

    static String seekStep(int seconds) {
        return seconds >= 60 ? seconds / 60 + "m" : seconds + "s";
    }

    /** {@code 8.0}, {@code 0.5}, {@code 0.25}, {@code 0.05}: two decimals with one trailing zero dropped. */
    static String rateText(double rate) {
        String text = String.format("%.2f", rate);
        return text.endsWith("0") ? text.substring(0, text.length() - 1) : text;
    }

    public static String stamp(long ticks) {
        long seconds = Math.max(0, ticks) / 20;
        return "%02d:%02d".formatted(seconds / 60, seconds % 60);
    }
}
