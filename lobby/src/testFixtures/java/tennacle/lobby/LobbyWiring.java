package tennacle.lobby;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.item.Material;
import tennacle.games.Matches;
import tennacle.games.Rooms;
import tennacle.games.TestWiring;
import tennacle.games.matchmaking.Duels;
import tennacle.lobby.menus.PlayerMenu;
import tennacle.social.party.Parties;

/** The games' wiring plus the lobby's, over the harness world as the main lobby. */
public final class LobbyWiring {

    private static boolean wired;

    private LobbyWiring() {}

    public static synchronized void ensure(InstanceContainer lobby) {
        TestWiring.ensure(lobby);
        if (wired) return;
        wired = true;
        if (Lobbies.all().isEmpty()) {
            Lobbies.add("main", "Main Lobby", Material.BEACON, "", mode -> true, lobby, new Pos(0.5, 65, 0.5));
        }
        Matches.home(player -> Lobbies.send(player, Lobbies.home(player)));
        Duels.picker(PlayerMenu::duel);
        Parties.warps(new Warps());
        // a lobby reader's own setting says whose lines reach them
        Rooms.hears((speaker, reader) -> Lobbies.in(speaker) == null || Settings.LOBBY_CHAT.of(reader).admits(speaker, reader));
        Settings.install();
        Hotbar.install();
        Counts.install();
    }
}
