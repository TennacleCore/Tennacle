package tennacle.lobby.menus;

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
import tennacle.games.watch.ReplayCatalog;

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
    private static volatile ReplayCatalog catalog = ReplayCatalog.SAMPLES;

    private static final List<Tab> TABS = List.of(
            new Tab("profile", v -> Items.head(v.getSkin(), Component.empty()), "Profile", "Your stats in every game.",
                    v -> profile(v.getUuid(), v.getUsername(), true)),
            new Tab("replays", v -> ItemStack.of(Material.BOOK), "Replays", "Watch the games you played.", v -> replays(v, 0)),
            new Tab("kits", v -> ItemStack.of(Material.ANVIL), "Kits", "Edit your layout for each game.", v -> kits()));

    private MainMenu() {}

    /** Where the replays tab lists games from; samples until the database exists. */
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
            screen.item(slots[i], v -> card(v, subject, mode, card));
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
        }
        return entry.render(viewer);
    }

    private static @Nullable PlayerSkin skinOf(UUID id) {
        Player online = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(id);
        return online != null ? online.getSkin() : null;
    }

    private static Screen replays(Player viewer, int page) {
        List<ReplayCatalog.Record> games = catalog.of(viewer.getUuid(), viewer.getUsername());
        Screen.Builder screen = hub("replays", "Replays").key("menu:replays");
        if (games.isEmpty()) {
            screen.item(31, v -> Entry.of(Material.PAPER).name("No Games Yet").text("Games you play will show up here!").render(v));
            return screen.build();
        }
        return screen.pages(PAGE, games.size(), page, i -> v -> replay(v, games.get(i)),
                i -> (v, click) -> games.get(i).sample() ? "This is a sample replay!" : null,
                to -> replays(viewer, to)).build();
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
        Entry entry = Entry.of(Icons.titled(game.mode())).name(game.mode())
                .text(WHEN.format(Instant.ofEpochMilli(game.playedAt())), "Duration: " + clock(game.lengthMillis()))
                .fact("Map", game.map())
                .fact("Result", game.winner() == null ? theme.prose("Draw") : win ? theme.good("Win") : theme.bad("Loss"));
        List<ReplayCatalog.Side> shown = new ArrayList<>();
        if (won != null) shown.add(won);
        if (mine != null && mine != won) shown.add(mine);
        for (ReplayCatalog.Side side : game.sides()) if (!shown.contains(side) && shown.size() < 3) shown.add(side);
        for (ReplayCatalog.Side side : shown) {
            NamedTextColor color = NamedTextColor.NAMES.valueOr(side.team(), NamedTextColor.WHITE);
            String names = String.join(", ", side.seats().stream().map(ReplayCatalog.Seat::name).toList());
            entry.line(Component.text(titled(side.team()) + ": ", color).append(Component.text(names, NamedTextColor.WHITE)));
        }
        int more = game.sides().size() - shown.size();
        if (more > 0) entry.line(theme.prose("+" + more + " more teams"));
        return entry.action("Click to watch!").render(viewer);
    }

    private static Screen kits() {
        Screen.Builder screen = hub("kits", "Kits");
        List<KitLayouts.Editable> kits = KitLayouts.editables();
        int[] slots = Rows.centered(3, kits.size());
        for (int i = 0; i < kits.size(); i++) {
            KitLayouts.Editable kit = kits.get(i);
            screen.press(slots[i], v -> {
                Entry entry = Entry.of(Icons.of(kit.id())).name(kit.title() + " Layout Editor")
                        .text(kit.screen() != null ? "Set what goes in each hotbar slot for " + kit.title() + "."
                                : "Edit your inventory layout for " + kit.title() + ".");
                if (kit.kit().armorMoves()) entry.text("Your armor can be moved too.");
                return entry.action("Click to edit!").render(v);
            }, (v, click) -> {
                String refused = KitLayouts.open(v, kit.id());
                if (refused == null && kit.screen() == null) v.closeInventory(); // the editor is your own inventory
                return refused;
            });
        }
        return screen.build();
    }

    static String clock(long millis) {
        long seconds = millis / 1000;
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    private static String titled(String word) {
        return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
