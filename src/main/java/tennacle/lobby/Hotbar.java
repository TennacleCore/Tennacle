package tennacle.lobby;

import io.github.term4.archipelago.shard.ShardView;
import io.github.term4.canvas.Canvas;
import io.github.term4.canvas.screen.Entry;
import io.github.term4.canvas.theme.Theme;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.EventListener;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.item.ItemDropEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.event.player.PlayerSwapItemEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;
import tennacle.Press;
import tennacle.games.KitLayouts;
import tennacle.lobby.menus.LobbyMenu;
import tennacle.lobby.menus.MainMenu;
import tennacle.lobby.menus.PlayerMenu;
import tennacle.lobby.menus.SettingsMenu;

/**
 * The lobby hotbar: the lobby switcher, the menu, whether you see other players, your settings. The items stay
 * where they are, and a right-click opens each; one press, however many packets the client sends for it.
 */
public final class Hotbar {

    private static final Tag<String> ITEM = Tag.String("tennacle:lobby-item");
    private static final Tag<Long> TOGGLED = Tag.Transient("tennacle:players-toggled");
    private static final long TOGGLE_GAP_MS = 1000;
    private static final java.util.concurrent.atomic.AtomicBoolean INSTALLED = new java.util.concurrent.atomic.AtomicBoolean();
    static final int LOBBIES = 0, MENU = 4, PLAYERS = 7, SETTINGS = 8;

    private Hotbar() {}

    public static boolean isLobbyItem(@NotNull ItemStack item) {
        return item.getTag(ITEM) != null;
    }

    /** The four items in their slots, and the players the setting shows. */
    public static void dress(@NotNull Player player) {
        var inv = player.getInventory();
        inv.setItemStack(LOBBIES, lobbies(player));
        inv.setItemStack(MENU, tagged(Entry.of(Material.ENDER_CHEST).name("Menu")
                .text("Profile, party, replays,", "live games and kits.").action("Right-click to open").render(player), "menu"));
        inv.setItemStack(PLAYERS, players(player));
        inv.setItemStack(SETTINGS, tagged(Entry.of(Material.NETHER_STAR).name("Settings")
                .text("Sounds, chat and requests.").action("Right-click to open").render(player), "settings"));
        sight(player);
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        var events = MinecraftServer.getGlobalEventHandler();
        // listened past a cancel, and both events: one press reaches us as either or both ({@link Press})
        events.addListener(EventListener.builder(PlayerUseItemEvent.class).ignoreCancelled(false)
                .handler(e -> pressed(e.getPlayer(), e.getHand())).build());
        events.addListener(EventListener.builder(PlayerBlockInteractEvent.class).ignoreCancelled(false)
                .handler(e -> pressed(e.getPlayer(), e.getHand())).build());
        events.addListener(PlayerEntityInteractEvent.class, e -> {
            Player player = e.getPlayer();
            if (e.getHand() != PlayerHand.MAIN || !(e.getTarget() instanceof Player other)) return;
            if (Lobbies.in(player) == null || KitLayouts.editing(player) || !Press.once(player)) return;
            PlayerMenu.open(player, other);
        });
        events.addListener(InventoryPreClickEvent.class, e -> {
            Player player = e.getPlayer();
            if (e.getInventory() == player.getInventory() && carries(player)) e.setCancelled(true);
        });
        events.addListener(ItemDropEvent.class, e -> {
            if (isLobbyItem(e.getItemStack())) e.setCancelled(true);
        });
        events.addListener(PlayerSwapItemEvent.class, e -> {
            if (isLobbyItem(e.getMainHandItem()) || isLobbyItem(e.getOffHandItem())) e.setCancelled(true);
        });
    }

    private static void pressed(Player player, PlayerHand hand) {
        if (hand != PlayerHand.MAIN) return;
        String item = player.getItemInMainHand().getTag(ITEM);
        if (item == null || Lobbies.in(player) == null || !Press.once(player)) return;
        switch (item) {
            case "lobbies" -> LobbyMenu.open(player);
            case "menu" -> MainMenu.open(player);
            case "players" -> togglePlayers(player);
            case "settings" -> SettingsMenu.open(player);
            default -> {}
        }
    }

    private static void togglePlayers(Player player) {
        long now = System.currentTimeMillis();
        Long last = player.getTag(TOGGLED);
        if (last != null && now - last < TOGGLE_GAP_MS) {
            player.sendActionBar(Canvas.themeFor(player).prose("Wait a second"));
            return;
        }
        player.setTag(TOGGLED, now);
        Settings.playersShown(player, !Settings.playersShown(player));
        player.getInventory().setItemStack(PLAYERS, players(player));
        sight(player);
    }

    // other players drop out of view; the NPCs and everything else stay
    private static void sight(Player player) {
        ShardView.set(player, Settings.playersShown(player) ? null
                : ShardView.base().withBaseEntities(entity -> !(entity instanceof Player)));
    }

    private static boolean carries(Player player) {
        for (int slot = 0; slot < 9; slot++) {
            if (isLobbyItem(player.getInventory().getItemStack(slot))) return true;
        }
        return false;
    }

    private static ItemStack lobbies(Player player) {
        Lobbies.Lobby here = Lobbies.in(player);
        Entry entry = Entry.of(Material.COMPASS).name("Lobbies").text("Go to another game's lobby.");
        if (here != null) entry.fact("You're in", here.name());
        return tagged(entry.action("Right-click to open").render(player), "lobbies");
    }

    private static ItemStack players(Player player) {
        Theme theme = Canvas.themeFor(player);
        boolean shown = Settings.playersShown(player);
        Component name = theme.value("Players: ").append(shown ? theme.good("Shown") : theme.prose("Hidden"));
        return tagged(Entry.of(shown ? Material.LIME_DYE : Material.GRAY_DYE).name(name)
                .text("Everyone else in the lobby.").action(shown ? "Right-click to hide them" : "Right-click to show them")
                .render(player), "players");
    }

    private static ItemStack tagged(ItemStack item, String id) {
        return item.withTag(ITEM, id);
    }
}
