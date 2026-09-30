package tennacle.lobby.menus;

import io.github.term4.canvas.Canvas;
import io.github.term4.canvas.screen.Entry;
import io.github.term4.canvas.screen.Items;
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
import org.jetbrains.annotations.Nullable;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.matchmaking.Duels;
import tennacle.party.Parties;
import tennacle.lobby.Lobbies;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Right-clicking a player: invite them, duel them, see their stats. */
public final class PlayerMenu {

    // the rules a viewer's next duel request carries: a platform's name, absent for the house's own
    private static final Map<UUID, String> RULES = new ConcurrentHashMap<>();

    private PlayerMenu() {}

    public static void open(@NotNull Player viewer, @NotNull Player target) {
        String name = target.getUsername();
        Screens.open(viewer, Screen.rows(4).title(Component.text(name))
                .item(4, v -> head(v, target))
                .press(20, v -> invite(v, target), (v, click) -> {
                    String refused = refusedInvite(v, target);
                    if (refused != null) return refused;
                    Parties.invite(v, target);
                    v.closeInventory();
                    return null;
                })
                .press(22, v -> duelItem(v, target), (v, click) -> {
                    String refused = refusedDuel(v, target);
                    if (refused != null) return refused;
                    duel(v, target);
                    return null;
                })
                .press(24, v -> Entry.of(Material.PAPER).name("View Stats").text("See " + name + "'s stats in every game.")
                        .action("Click to view!").render(v), (v, click) -> {
                    Screens.push(v, MainMenu.profile(target.getUuid(), name, false));
                    return null;
                })
                .close(31)
                .build());
    }

    /** The duel picker for {@code target}: the game, whose rules, then the request. */
    public static void duel(@NotNull Player viewer, @NotNull Player target) {
        List<Mode> modes = new ArrayList<>();
        for (String name : Matches.launchedFull()) {
            Mode mode = Matches.mode(name);
            if (mode != null) modes.add(mode);
        }
        int[] slots = Rows.centered(1, modes.size());
        Screen.Builder screen = Screen.rows(4).title(Component.text("Duel " + target.getUsername())).back(30).close(31);
        for (int i = 0; i < modes.size(); i++) {
            Mode mode = modes.get(i);
            screen.press(slots[i], v -> {
                Entry entry = Entry.of(Icons.of(mode)).name(mode.title());
                String sides = sides(v, target);
                if (sides != null) entry.text(sides);
                return entry.action("Click to send the request!").render(v);
            }, (v, click) -> {
                if (!target.isOnline()) return target.getUsername() + " is no longer online!";
                String refused = refusedDuel(v, target);
                if (refused != null) return refused;
                v.closeInventory();
                Duels.request(v, target, mode, RULES.get(v.getUuid()));
                return null;
            });
        }
        screen.press(32, PlayerMenu::rules, (v, click) -> {
            List<String> presets = presets();
            int at = presets.indexOf(RULES.getOrDefault(v.getUuid(), ""));
            String next = presets.get(Math.floorMod(at + (click instanceof Click.Right ? -1 : 1), presets.size()));
            if (next.isEmpty()) RULES.remove(v.getUuid());
            else RULES.put(v.getUuid(), next);
            Screens.repaint(v);
            return null;
        });
        if (Screens.screen(viewer) != null) Screens.push(viewer, screen.build());
        else Screens.open(viewer, screen.build());
    }

    private static ItemStack head(Player viewer, Player target) {
        Lobbies.Lobby lobby = Lobbies.in(target);
        List<Parties.Member> party = Parties.members(target);
        return Entry.of(Items.head(target.getSkin(), Component.empty())).name(target.getUsername())
                .fact("Lobby", lobby != null ? lobby.name() : "In a game")
                .fact("Party", party.size() > 1 ? party.getFirst().name() + " (" + party.size() + ")" : "None")
                .render(viewer);
    }

    private static ItemStack invite(Player viewer, Player target) {
        String refused = refusedInvite(viewer, target);
        if (refused != null) return unavailable(viewer, "Invite to Party", refused);
        return Entry.of(Material.CAKE).name("Invite to Party").text("Invite " + target.getUsername() + " to your party.")
                .action("Click to invite!").render(viewer);
    }

    private static ItemStack duelItem(Player viewer, Player target) {
        String refused = refusedDuel(viewer, target);
        if (refused != null) return unavailable(viewer, "Send Duel Request", refused);
        return Entry.of(Material.IRON_SWORD).name("Send Duel Request").text("Challenge " + target.getUsername() + " to a duel.")
                .action("Click to pick a game!").render(viewer);
    }

    private static ItemStack unavailable(Player viewer, String name, String why) {
        return Entry.of(Material.GRAY_STAINED_GLASS_PANE).name(name).danger().text(why).render(viewer);
    }

    private static @Nullable String refusedInvite(Player viewer, Player target) {
        if (!target.isOnline()) return target.getUsername() + " is no longer online!";
        if (Parties.together(viewer, target)) return target.getUsername() + " is already in your party!";
        if (!Parties.INVITES.of(target).admits(viewer, target)) return target.getUsername() + " has party invites disabled!";
        return null;
    }

    private static @Nullable String refusedDuel(Player viewer, Player target) {
        if (!target.isOnline()) return target.getUsername() + " is no longer online!";
        if (!Duels.REQUESTS.of(target).admits(viewer, target)) return target.getUsername() + " has duel requests disabled!";
        return null;
    }

    private static @Nullable String sides(Player viewer, Player target) {
        if (Parties.together(viewer, target)) return "Inside your party";
        int mine = Parties.of(viewer).size();
        int theirs = Parties.of(target).size();
        return mine == 1 && theirs == 1 ? null : "Your party of " + mine + " against " + theirs;
    }

    private static ItemStack rules(Player viewer) {
        Theme theme = Canvas.screenThemeFor(viewer);
        String current = RULES.getOrDefault(viewer.getUuid(), "");
        Entry entry = Entry.of(Material.COMPARATOR).name("Rules").text("Change whose rules the duel uses.")
                .line(theme.prose("Options:"));
        for (String preset : presets()) {
            String label = preset.isEmpty() ? "House" : Character.toUpperCase(preset.charAt(0)) + preset.substring(1);
            entry.line(preset.equals(current) ? theme.good("➠ " + label)
                    : theme.prose("   ").append(Component.text(label, NamedTextColor.WHITE)));
        }
        return entry.action("Click to change!").render(viewer);
    }

    private static List<String> presets() {
        List<String> out = new ArrayList<>();
        out.add("");
        out.addAll(Matches.platforms());
        return out;
    }
}
