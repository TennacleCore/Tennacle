package tennacle.lobby.menus;

import io.github.term4.canvas.Canvas;
import io.github.term4.canvas.screen.Entry;
import io.github.term4.canvas.screen.Screen;
import io.github.term4.canvas.screen.Screens;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import tennacle.lobby.Counts;
import tennacle.lobby.Lobbies;

import java.util.List;

/** The compass: every lobby, the one you are in marked. */
public final class LobbyMenu {

    private LobbyMenu() {}

    public static void open(@NotNull Player viewer) {
        List<Lobbies.Lobby> lobbies = Lobbies.all();
        int[] slots = Rows.centered(1, lobbies.size());
        Screen.Builder screen = Screen.rows(3).key(Counts.KEY).title(Component.text("Game Menu")).close(22);
        for (int i = 0; i < lobbies.size(); i++) {
            Lobbies.Lobby lobby = lobbies.get(i);
            screen.press(slots[i], v -> item(v, lobby), (v, click) -> {
                if (Lobbies.in(v) == lobby) return "You are already in this lobby!";
                v.closeInventory();
                Lobbies.send(v, lobby);
                return null;
            });
        }
        Screens.open(viewer, screen.build());
    }

    private static ItemStack item(Player viewer, Lobbies.Lobby lobby) {
        boolean here = Lobbies.in(viewer) == lobby;
        Entry entry = Entry.of(lobby.icon()).name(lobby.name()).text(lobby.blurb())
                .fact("Players", Counts.inLobby(lobby));
        if (lobby != Lobbies.main()) entry.fact("Playing", Counts.playing(lobby));
        if (here) entry.danger().line(Canvas.screenThemeFor(viewer).bad("Already connected!"));
        else entry.action("Click to connect!");
        return entry.render(viewer);
    }
}
