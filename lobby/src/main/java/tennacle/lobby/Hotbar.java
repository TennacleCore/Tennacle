package tennacle.lobby;

import io.github.term4.archipelago.shard.ShardView;
import io.github.term4.canvas.Canvas;
import io.github.term4.canvas.screen.Items;
import io.github.term4.canvas.theme.Theme;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.EventListener;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.item.ItemDropEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.event.player.PlayerSwapItemEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;
import tennacle.Msg;
import tennacle.Press;
import tennacle.games.KitLayouts;
import tennacle.social.party.Parties;
import tennacle.games.matchmaking.Queues;
import tennacle.lobby.menus.LobbyMenu;
import tennacle.lobby.menus.MainMenu;
import tennacle.lobby.menus.PlayerMenu;
import tennacle.lobby.menus.SettingsMenu;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The lobby hotbar: the game menu, the menu, whether you see other players, your settings, and while your side
 * waits in a queue, the way out of it. The items stay where they are, and a right-click uses each; one press,
 * however many packets the client sends for it.
 */
public final class Hotbar {

    private static final Tag<String> ITEM = Tag.String("tennacle:lobby-item");
    private static final Tag<Long> TOGGLED = Tag.Transient("tennacle:players-toggled");
    private static final long TOGGLE_GAP_MS = 1000;
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    static final int GAMES = 0, MENU = 4, PLAYERS = 6, SETTINGS = 7, LEAVE = 8;

    private Hotbar() {}

    public static boolean isLobbyItem(@NotNull ItemStack item) {
        return item.getTag(ITEM) != null;
    }

    /** The items in their slots, and the players the setting shows. */
    public static void dress(@NotNull Player player) {
        var inv = player.getInventory();
        inv.setItemStack(GAMES, item(player, Material.COMPASS, "Game Menu", "Right-click to switch lobbies!", "games"));
        inv.setItemStack(MENU, item(player, Material.ENDER_CHEST, "Menu", "Right-click to view your stats, replays and kits!", "menu"));
        inv.setItemStack(PLAYERS, players(player));
        inv.setItemStack(SETTINGS, item(player, Material.NETHER_STAR, "Settings", "Right-click to change your settings!", "settings"));
        queued(player);
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
        // creative places what it holds; an ender chest menu is still a block
        events.addListener(PlayerBlockPlaceEvent.class, e -> {
            if (isLobbyItem(e.getPlayer().getItemInHand(e.getHand()))) e.setCancelled(true);
        });
        Counts.onMove(() -> {
            for (Player player : MinecraftServer.getConnectionManager().getOnlinePlayers()) queued(player);
        });
    }

    /** The last slot while {@code player}'s side waits in a queue, empty once it stops. */
    static void queued(@NotNull Player player) {
        if (Lobbies.in(player) == null) return;
        ItemStack leave = Queues.of(player) == null ? ItemStack.AIR : leave(player);
        if (!player.getInventory().getItemStack(LEAVE).equals(leave)) player.getInventory().setItemStack(LEAVE, leave);
    }

    private static void pressed(Player player, PlayerHand hand) {
        if (hand != PlayerHand.MAIN) return;
        String item = player.getItemInMainHand().getTag(ITEM);
        if (item == null || Lobbies.in(player) == null || !Press.once(player)) return;
        switch (item) {
            case "games" -> LobbyMenu.open(player);
            case "menu" -> MainMenu.open(player);
            case "players" -> togglePlayers(player);
            case "settings" -> SettingsMenu.open(player);
            case "leave" -> leaveQueue(player);
            default -> {}
        }
    }

    private static void togglePlayers(Player player) {
        long now = System.currentTimeMillis();
        Long last = player.getTag(TOGGLED);
        if (last != null && now - last < TOGGLE_GAP_MS) {
            Msg.fail(player, "You must wait a second between uses!");
            return;
        }
        player.setTag(TOGGLED, now);
        Settings.playersShown(player, !Settings.playersShown(player));
        player.getInventory().setItemStack(PLAYERS, players(player));
        sight(player);
    }

    private static void leaveQueue(Player player) {
        if (Queues.of(player) == null) return;
        if (!Parties.leads(player)) {
            Msg.fail(player, Parties.NOT_LEADER);
            return;
        }
        Queues.leave(player);
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

    private static ItemStack item(Player player, Material material, String name, String lore, String id) {
        Theme theme = Canvas.screenThemeFor(player);
        return usable(player, material, theme.value(name), lore, id);
    }

    private static ItemStack players(Player player) {
        Theme theme = Canvas.screenThemeFor(player);
        boolean shown = Settings.playersShown(player);
        Component name = Component.text("Players: ", NamedTextColor.WHITE).append(shown ? theme.good("Visible") : theme.bad("Hidden"));
        return usable(player, shown ? Material.LIME_DYE : Material.GRAY_DYE, name, "Right-click to toggle player visibility!", "players");
    }

    private static ItemStack leave(Player player) {
        return usable(player, Material.RED_DYE, Canvas.screenThemeFor(player).bad("Leave Queue"), "Right-click to leave the queue!", "leave");
    }

    private static ItemStack usable(Player player, Material material, Component name, String lore, String id) {
        Theme theme = Canvas.screenThemeFor(player);
        ItemStack item = Items.named(ItemStack.of(material), name.append(theme.prose(" (Right Click)")));
        return Items.lore(item, List.of(theme.prose(lore))).withTag(ITEM, id);
    }
}
