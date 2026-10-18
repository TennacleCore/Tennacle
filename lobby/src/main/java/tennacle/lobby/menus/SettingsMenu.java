package tennacle.lobby.menus;

import io.github.term4.canvas.Canvas;
import io.github.term4.canvas.screen.Entry;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.canvas.theme.Theme;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import tennacle.games.matchmaking.Duels;
import tennacle.lobby.Settings;
import tennacle.social.Audience;
import tennacle.social.Choice;
import tennacle.social.friends.FriendCommands;
import tennacle.social.friends.Friends;
import tennacle.social.messages.Messages;
import tennacle.social.party.Parties;
import tennacle.looks.GameSounds;

import java.util.function.Function;

/** The nether star: lobby sounds and chat, friend notices, and who may reach you how; each setting over its dye. */
public final class SettingsMenu {

    private SettingsMenu() {}

    public static void open(@NotNull Player viewer) {
        Screen.Builder screen = Screen.rows(6).title(Component.text("Settings")).close(49);
        setting(screen, 10, SettingsMenu::sounds, v -> GameSounds.lobbyMuted(v) ? Material.GRAY_DYE : Material.LIME_DYE,
                (v, click) -> {
                    GameSounds.lobbyMuted(v, !GameSounds.lobbyMuted(v));
                    Screens.repaint(v);
                    return null;
                });
        choice(screen, 12, Material.PAPER, "Lobby Chat", "Change whose messages you see in lobbies.", Settings.LOBBY_CHAT);
        setting(screen, 14, SettingsMenu::notices, v -> switch (Friends.notices(v)) {
            case ALL -> Material.LIME_DYE;
            case BEST -> Material.ORANGE_DYE;
            case OFF -> Material.GRAY_DYE;
        }, (v, click) -> {
            Friends.Notices[] all = Friends.Notices.values();
            int at = Friends.notices(v).ordinal() + (click instanceof Click.Right ? -1 : 1);
            Friends.notices(v, all[Math.floorMod(at, all.length)]);
            Screens.repaint(v);
            return null;
        });
        choice(screen, 16, Material.WRITABLE_BOOK, "Friend Request Privacy", "Change who can send you friend requests.",
                FriendCommands.REQUESTS);
        choice(screen, 28, Material.OAK_SIGN, "Private Message Privacy", "Change who can private message you.", Messages.PRIVACY);
        choice(screen, 30, Material.BOOK, "Party Invite Privacy", "Change who can send you party invites.", Parties.INVITES);
        choice(screen, 32, Material.IRON_SWORD, "Duel Request Privacy", "Change who can send you duel requests.", Duels.REQUESTS);
        choice(screen, 34, Material.CHEST, "Kit Visibility", "Change who can see and copy your kit layouts.",
                tennacle.games.imports.LayoutVisibility.VISIBILITY);
        Screens.open(viewer, screen.build());
    }

    private static void setting(Screen.Builder screen, int slot, Function<Player, ItemStack> item,
                                Function<Player, Material> dye, Screen.Answer answer) {
        screen.press(slot, item, answer);
        screen.press(slot + 9, v -> item.apply(v).withMaterial(dye.apply(v)), answer);
    }

    private static ItemStack sounds(Player viewer) {
        boolean on = !GameSounds.lobbyMuted(viewer);
        Theme theme = Canvas.screenThemeFor(viewer);
        Entry entry = Entry.of(Material.NOTE_BLOCK).name("Lobby Sounds").text("Toggles menu clicks and alerts in lobbies.")
                .fact("Currently", on ? theme.good("ENABLED") : theme.bad("DISABLED"))
                .action(on ? "Click to disable!" : "Click to enable!");
        if (!on) entry.danger();
        return entry.render(viewer);
    }

    private static ItemStack notices(Player viewer) {
        Theme theme = Canvas.screenThemeFor(viewer);
        Friends.Notices now = Friends.notices(viewer);
        Entry entry = Entry.of(Material.PAPER).name("Friend Notifications")
                .text("Change whose joins and leaves you hear about.").line(theme.prose("Options:"));
        for (Friends.Notices option : Friends.Notices.values()) {
            String label = switch (option) {
                case ALL -> "All friends";
                case BEST -> "Best friends";
                case OFF -> "Nobody";
            };
            entry.line(option == now ? theme.good("➠ " + label)
                    : theme.prose("   ").append(Component.text(label, NamedTextColor.WHITE)));
        }
        return entry.action("Click to change!").render(viewer);
    }

    private static void choice(Screen.Builder screen, int slot, Material icon, String name, String text, Choice choice) {
        setting(screen, slot, v -> {
            Theme theme = Canvas.screenThemeFor(v);
            Audience now = choice.of(v);
            Entry entry = Entry.of(icon).name(name).text(text).line(theme.prose("Options:"));
            for (Audience option : choice.options()) {
                entry.line(option == now ? theme.good("➠ " + option.label())
                        : theme.prose("   ").append(Component.text(option.label(), NamedTextColor.WHITE)));
            }
            return entry.action("Click to change!").render(v);
        }, v -> switch (choice.of(v)) {
            case EVERYONE -> Material.LIME_DYE;
            case FRIENDS -> Material.ORANGE_DYE;
            case PARTY -> Material.YELLOW_DYE;
            case NOBODY -> Material.GRAY_DYE;
        }, (v, click) -> {
            choice.step(v, click instanceof Click.Right ? -1 : 1);
            Screens.repaint(v);
            return null;
        });
    }
}
