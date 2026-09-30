package tennacle.lobby.menus;

import io.github.term4.canvas.Canvas;
import io.github.term4.canvas.screen.Entry;
import io.github.term4.canvas.screen.Items;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.canvas.theme.Theme;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.matchmaking.Duels;
import tennacle.games.matchmaking.Parties;
import tennacle.lobby.Lobbies;
import tennacle.lobby.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Right-clicking a player: invite them, duel them, see their numbers. */
public final class PlayerMenu {

    // the rules a viewer's next duel request carries: a platform's name, absent for the house's own
    private static final Map<UUID, String> RULES = new ConcurrentHashMap<>();

    private PlayerMenu() {}

    public static void open(@NotNull Player viewer, @NotNull Player target) {
        String name = target.getUsername();
        Screens.open(viewer, Screen.rows(3).title(Component.text(name))
                .item(4, v -> head(v, target))
                .press(11, v -> invite(v, target), (v, click) -> {
                    String refused = refusedInvite(v, target);
                    if (refused != null) return refused;
                    Parties.invite(v, target);
                    v.closeInventory();
                    return null;
                })
                .press(13, v -> duelItem(v, target), (v, click) -> {
                    String refused = refusedDuel(v, target);
                    if (refused != null) return refused;
                    duel(v, target);
                    return null;
                })
                .press(15, v -> Entry.of(Material.BOOK).name("Stats").text(name + "'s numbers in every game.")
                        .action("Click to see").render(v), (v, click) -> {
                    Screens.push(v, MainMenu.profile(target.getUuid(), name, false));
                    return null;
                })
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
        Screen.Builder screen = Screen.rows(3).title(Component.text("Duel " + target.getUsername())).back(18).close(26);
        for (int i = 0; i < modes.size(); i++) {
            Mode mode = modes.get(i);
            screen.press(slots[i], v -> Entry.of(Icons.of(mode)).name(mode.title()).kind(sides(v, target))
                    .action("Click to send the request").render(v), (v, click) -> {
                if (!target.isOnline()) return target.getUsername() + " left.";
                String refused = refusedDuel(v, target);
                if (refused != null) return refused;
                v.closeInventory();
                Duels.request(v, target, mode, RULES.get(v.getUuid()));
                return null;
            });
        }
        screen.press(22, PlayerMenu::rules, (v, click) -> {
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
        Entry entry = Entry.of(Items.head(target.getSkin(), Component.empty())).name(target.getUsername())
                .kind(lobby != null ? "In the " + lobby.name() + (lobby == Lobbies.main() ? "" : " lobby") : "Playing")
                .fact("Party", party.size() > 1 ? party.getFirst().name() + " (" + party.size() + ")" : "None")
                .fact("Duel requests", Settings.DUEL_REQUESTS.of(target).label());
        return entry.render(viewer);
    }

    private static ItemStack invite(Player viewer, Player target) {
        String refused = refusedInvite(viewer, target);
        if (refused != null) return pane(viewer, "Invite to party", refused);
        return Entry.of(Material.CAKE).name("Invite to party").text("Asks " + target.getUsername() + " into your party.")
                .action("Click to invite").render(viewer);
    }

    private static ItemStack duelItem(Player viewer, Player target) {
        String refused = refusedDuel(viewer, target);
        if (refused != null) return pane(viewer, "Duel", refused);
        return Entry.of(Material.IRON_SWORD).name("Duel").text("Pick a game, then send it.").action("Click to choose").render(viewer);
    }

    private static ItemStack pane(Player viewer, String name, String why) {
        return Entry.of(Material.GRAY_STAINED_GLASS_PANE).name(Canvas.themeFor(viewer).dim(name)).text(why).render(viewer);
    }

    private static @Nullable String refusedInvite(Player viewer, Player target) {
        if (!target.isOnline()) return target.getUsername() + " left.";
        if (Parties.together(viewer, target)) return target.getUsername() + " is in your party.";
        if (!Settings.PARTY_INVITES.of(target).admits(viewer, target)) return target.getUsername() + " takes no party invites.";
        return null;
    }

    private static @Nullable String refusedDuel(Player viewer, Player target) {
        if (!target.isOnline()) return target.getUsername() + " left.";
        if (!Settings.DUEL_REQUESTS.of(target).admits(viewer, target)) return target.getUsername() + " takes no duel requests.";
        return null;
    }

    private static String sides(Player viewer, Player target) {
        if (Parties.together(viewer, target)) return "One on one, inside your party";
        int mine = Parties.of(viewer).size();
        int theirs = Parties.of(target).size();
        return mine == 1 && theirs == 1 ? "One on one" : "Your party of " + mine + " against " + theirs;
    }

    private static ItemStack rules(Player viewer) {
        Theme theme = Canvas.themeFor(viewer);
        String current = RULES.getOrDefault(viewer.getUuid(), "");
        Entry entry = Entry.of(Material.COMPARATOR).name("Rules").text("Whose rules the duel plays by.");
        for (String preset : presets()) {
            String label = preset.isEmpty() ? "House" : Character.toUpperCase(preset.charAt(0)) + preset.substring(1);
            entry.line(preset.equals(current) ? theme.value("▸ " + label) : theme.dim("  " + label));
        }
        return entry.action("Click for the next").action("Right-click for the previous").render(viewer);
    }

    private static List<String> presets() {
        List<String> out = new ArrayList<>();
        out.add("");
        out.addAll(Matches.platforms());
        return out;
    }
}
