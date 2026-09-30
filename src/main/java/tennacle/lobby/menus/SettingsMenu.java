package tennacle.lobby.menus;

import io.github.term4.canvas.Canvas;
import io.github.term4.canvas.screen.Entry;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.canvas.theme.Theme;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import tennacle.lobby.Settings;
import tennacle.looks.GameSounds;

/** The nether star: lobby sounds, lobby chat, and who may invite or challenge you. */
public final class SettingsMenu {

    private SettingsMenu() {}

    public static void open(@NotNull Player viewer) {
        Screens.open(viewer, Screen.rows(3).title(Component.text("Settings"))
                .press(10, SettingsMenu::sounds, (v, click) -> {
                    GameSounds.lobbyMuted(v, !GameSounds.lobbyMuted(v));
                    Screens.repaint(v);
                    return null;
                })
                .press(12, v -> choice(v, Material.PAPER, "Lobby chat", "Whose messages you see.", Settings.LOBBY_CHAT),
                        (v, click) -> step(v, click, Settings.LOBBY_CHAT))
                .press(14, v -> choice(v, Material.CAKE, "Party invites", "Who can invite you.", Settings.PARTY_INVITES),
                        (v, click) -> step(v, click, Settings.PARTY_INVITES))
                .press(16, v -> choice(v, Material.IRON_SWORD, "Duel requests", "Who can challenge you.", Settings.DUEL_REQUESTS),
                        (v, click) -> step(v, click, Settings.DUEL_REQUESTS))
                .build());
    }

    private static ItemStack sounds(Player viewer) {
        boolean on = !GameSounds.lobbyMuted(viewer);
        Theme theme = Canvas.themeFor(viewer);
        return Entry.of(Material.NOTE_BLOCK).name("Lobby sounds").text("Clicks, alerts and pings.")
                .fact("Now", on ? theme.good("On") : theme.bad("Off"))
                .action(on ? "Click to turn off" : "Click to turn on").glint(on).render(viewer);
    }

    private static ItemStack choice(Player viewer, Material icon, String name, String text, Settings.Choice choice) {
        Theme theme = Canvas.themeFor(viewer);
        Settings.Audience now = choice.of(viewer);
        Entry entry = Entry.of(icon).name(name).text(text);
        for (Settings.Audience option : choice.options()) {
            entry.line(option == now ? theme.value("▸ " + option.label()) : theme.dim("  " + option.label()));
        }
        if (choice.options().size() == 2) entry.action("Click to switch");
        else entry.action("Click for the next").action("Right-click for the previous");
        return entry.render(viewer);
    }

    private static String step(Player viewer, Click click, Settings.Choice choice) {
        choice.step(viewer, click instanceof Click.Right ? -1 : 1);
        Screens.repaint(viewer);
        return null;
    }
}
