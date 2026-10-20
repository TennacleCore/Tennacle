package tennacle.lobby.menus;

import tennacle.storage.ReplayStore;
import tennacle.games.watch.Tapes;
import tennacle.Msg;
import net.minestom.server.inventory.click.Click;
import io.github.term4.canvas.Canvas;
import io.github.term4.canvas.screen.Entry;
import io.github.term4.canvas.screen.Items;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.canvas.screen.Tab;
import io.github.term4.canvas.theme.Theme;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerSkin;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.games.Careers;
import tennacle.games.KitLayouts;
import tennacle.games.imports.ImportMenu;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.watch.Theaters;
import tennacle.storage.ReplayCatalog;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The ender chest: your stats, your replays, your kits. */
public final class MainMenu {

    private static final int[] PAGE = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private static final ItemStack PANE = ItemStack.of(Material.GRAY_STAINED_GLASS_PANE);
    private static final ItemStack OPEN_PANE = ItemStack.of(Material.LIME_STAINED_GLASS_PANE);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());
    private static final Map<UUID, Map<String, int[]>> CAREERS = new ConcurrentHashMap<>();
    private static final java.util.Set<String> READING = ConcurrentHashMap.newKeySet();
    private static final int LISTED = 45;
    private static final Map<UUID, List<ReplayCatalog.Record>> GAMES = new ConcurrentHashMap<>();
    private static volatile ReplayCatalog catalog = ReplayCatalog.SAMPLES;

    private static final List<Tab> TABS = List.of(
            new Tab("profile", v -> Items.head(v.getSkin(), Component.empty()), "Profile", "Your stats in every game.",
                    v -> profile(v.getUuid(), v.getUsername(), true)),
            new Tab("replays", v -> ItemStack.of(Material.BOOK), "Replays", "Watch the games you played.", v -> {
                GAMES.remove(v.getUuid()); // fresh each visit: a game just played shows up
                return replays(v, 0);
            }),
            new Tab("kits", v -> ItemStack.of(Material.ANVIL), "Kits", "Edit your layout for each game.", v -> kits()));

    private MainMenu() {}

    /** Where the replays tab lists games from; samples without a database. */
    public static void catalog(@NotNull ReplayCatalog source) {
        catalog = source;
    }

    public static void open(@NotNull Player viewer) {
        Screens.open(viewer, profile(viewer.getUuid(), viewer.getUsername(), true));
    }

    private static Screen.Builder hub(String tab, String title) {
        return Screen.rows(6).title(Component.text(title)).tabs(TABS, tab, PANE, OPEN_PANE).close(49);
    }

    /** {@code name}'s stats, a card per game that keeps them; as a tab, or on its own under a player menu. */
    public static @NotNull Screen profile(@NotNull UUID subject, @NotNull String name, boolean asTab) {
        Map<String, Careers.Card> cards = Careers.cards();
        CAREERS.remove(subject); // read fresh each time the stats are opened
        Screen.Builder screen = asTab ? hub("profile", "Profile").key("menu:profile")
                : Screen.rows(4).title(Component.text(name)).back(30).close(31);
        int headSlot = asTab ? 22 : 4;
        int cardRow = asTab ? 3 : 2;
        screen.item(headSlot, v -> Entry.of(Items.head(skinOf(subject), Component.empty())).name(name).render(v));
        List<String> modes = new ArrayList<>(cards.keySet());
        if (modes.isEmpty()) {
            screen.item(cardRow * 9 + 4, v -> Entry.of(Material.PAPER).name("No Stats Yet")
                    .text("Play a game to see your stats here!").render(v));
        }
        int[] slots = Rows.centered(cardRow, modes.size());
        for (int i = 0; i < modes.size(); i++) {
            String mode = modes.get(i);
            Careers.Card card = cards.get(mode);
            if (card.detail() == null) screen.item(slots[i], v -> card(v, subject, mode, card));
            else screen.press(slots[i], v -> card(v, subject, mode, card), (v, click) -> {
                Screens.push(v, card.detail().open(subject, name));
                return null;
            });
        }
        return screen.build();
    }

    private static ItemStack card(Player viewer, UUID subject, String mode, Careers.Card card) {
        Entry entry = Entry.of(Icons.of(mode)).name(card.title());
        int[] values = CAREERS.getOrDefault(subject, Map.of()).get(mode);
        if (values == null) {
            entry.text("Loading...");
            if (READING.add(subject + mode)) {
                Careers.read(subject, card).thenAccept(read -> {
                    CAREERS.computeIfAbsent(subject, id -> new ConcurrentHashMap<>()).put(mode, read);
                    READING.remove(subject + mode);
                    Screens.repaint(viewer);
                });
            }
        } else {
            for (Careers.Row row : card.rows()) entry.fact(row.label(), row.value().apply(values));
            if (card.detail() != null) entry.action("Click for all stats!");
        }
        return entry.render(viewer);
    }

    private static @Nullable PlayerSkin skinOf(UUID id) {
        Player online = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(id);
        return online != null ? online.getSkin() : null;
    }

    private static Screen replays(Player viewer, int page) {
        List<ReplayCatalog.Record> games = GAMES.get(viewer.getUuid());
        Screen.Builder screen = hub("replays", "Replays").key("menu:replays");
        if (games == null) {
            screen.item(31, v -> Entry.of(Material.CLOCK).name("Loading...").render(v));
            catalog.of(viewer.getUuid(), viewer.getUsername(), LISTED).whenComplete((found, failed) -> {
                GAMES.put(viewer.getUuid(), failed != null ? List.of() : found);
                MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
                    Screen open = Screens.screen(viewer);
                    if (open != null && "menu:replays".equals(open.key())) Screens.swap(viewer, replays(viewer, page));
                });
            });
            return screen.build();
        }
        if (games.isEmpty()) {
            screen.item(31, v -> Entry.of(Material.PAPER).name("No Games Yet").text("Games you play will show up here!").render(v));
            return screen.build();
        }
        return screen.pages(PAGE, games.size(), page, i -> v -> replay(v, games.get(i)),
                i -> (v, click) -> click instanceof Click.Right ? bookmark(v, games.get(i), page) : watch(v, games.get(i)),
                to -> replays(viewer, to)).build();
    }

    private static @Nullable String bookmark(Player viewer, ReplayCatalog.Record game, int page) {
        if (game.sample()) return "This is a sample replay!";
        if (!game.watchable()) return "This replay has expired.";
        Tapes.store().bookmark(viewer.getUuid(), game.id()).whenComplete((result, failed) ->
                MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
                    if (failed != null) {
                        Msg.fail(viewer, "That didn't save. Try again in a moment.");
                        return;
                    }
                    switch (result) {
                        case ADDED -> Msg.ok(viewer, "Bookmarked. It stays yours to watch after it would expire.");
                        case REMOVED -> Msg.ok(viewer, "Bookmark removed.");
                        case FULL -> Msg.fail(viewer, "You can bookmark " + ReplayStore.BOOKMARKS + " replays. Remove one first.");
                        case MISSING -> Msg.fail(viewer, "Bookmarks need the replay database, which this server doesn't have.");
                    }
                    GAMES.remove(viewer.getUuid());
                    Screen open = Screens.screen(viewer);
                    if (open != null && "menu:replays".equals(open.key())) Screens.swap(viewer, replays(viewer, page));
                }));
        return null;
    }

    private static @Nullable String watch(Player viewer, ReplayCatalog.Record game) {
        if (game.sample()) return "This is a sample replay!";
        if (!game.watchable()) return "This replay has expired.";
        viewer.closeInventory();
        Theaters.watch(viewer, game.id());
        return null;
    }

    private static ItemStack replay(Player viewer, ReplayCatalog.Record game) {
        Theme theme = Canvas.screenThemeFor(viewer);
        ReplayCatalog.Side mine = null;
        ReplayCatalog.Side won = null;
        for (ReplayCatalog.Side side : game.sides()) {
            if (side.team().equals(game.winner())) won = side;
            if (side.seats().stream().anyMatch(seat -> seat.id().equals(viewer.getUuid()))) mine = side;
        }
        boolean win = mine != null && mine == won;
        Mode mode = Matches.mode(game.mode());
        String title = mode != null ? mode.title() : game.mode();
        Entry entry = Entry.of(Icons.of(game.mode())).name(title)
                .text(WHEN.format(Instant.ofEpochMilli(game.playedAt())), "Duration: " + clock(game.lengthMillis()))
                .fact("Map", game.map())
                .fact("Result", game.winner() == null ? theme.prose("Draw") : win ? theme.good("Win") : theme.bad("Loss"));
        List<ReplayCatalog.Side> shown = new ArrayList<>();
        if (won != null) shown.add(won);
        if (mine != null && mine != won) shown.add(mine);
        for (ReplayCatalog.Side side : game.sides()) if (!shown.contains(side) && shown.size() < 3) shown.add(side);
        for (ReplayCatalog.Side side : shown) {
            NamedTextColor color = NamedTextColor.NAMES.valueOr(side.color(), NamedTextColor.WHITE);
            String names = String.join(", ", side.seats().stream().map(ReplayCatalog.Seat::name).toList());
            String label = side.name().isEmpty() ? "Team " + side.team() : side.name();
            entry.line(Component.text(label + ": ", color).append(Component.text(names, NamedTextColor.WHITE)));
        }
        int more = game.sides().size() - shown.size();
        if (more > 0) entry.line(theme.prose("+" + more + (more == 1 ? " more team" : " more teams")));
        if (game.bookmarked()) entry.line(theme.good("Bookmarked"));
        if (!game.watchable() && !game.sample()) return entry.action("Expired").render(viewer);
        return entry.action("Click to watch!").action(game.bookmarked() ? "Right-click to remove the bookmark" : "Right-click to bookmark")
                .render(viewer);
    }

    private static Screen kits() {
        Screen.Builder screen = hub("kits", "Kits");
        List<KitLayouts.Editable> kits = KitLayouts.editables();
        int[] slots = Rows.centered(3, kits.size());
        for (int i = 0; i < kits.size(); i++) {
            KitLayouts.Editable kit = kits.get(i);
            KitEditorButton.put(screen, slots[i], kit, Icons.of(kit.id()));
        }
        screen.button(40, v -> Entry.of(Material.BOOK).name("Import Layouts")
                .text("Copy your layouts in from", "another server, like Hypixel.").action("Click to choose!").render(v),
                (v, click) -> ImportMenu.open(v, null));
        return screen.build();
    }

    static String clock(long millis) {
        long seconds = millis / 1000;
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

}
