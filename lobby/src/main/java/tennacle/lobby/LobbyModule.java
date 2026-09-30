package tennacle.lobby;

import io.github.term4.canvas.tab.TabList;
import net.minestom.server.MinecraftServer;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.InstanceContainer;
import org.jetbrains.annotations.NotNull;
import tennacle.Msg;
import tennacle.games.Matches;
import tennacle.games.Rooms;
import tennacle.games.matchmaking.Duels;
import tennacle.lobby.menus.PlayerMenu;

/** The lobby module: the lobby worlds, and their hotbar, NPCs, menus, settings and chat rooms. */
public final class LobbyModule {

    private LobbyModule() {}

    /** The main lobby, the world a login lands in; the test pads ride {@code devCommands}. */
    public static @NotNull InstanceContainer world(boolean devCommands) {
        InstanceContainer world = Playground.createWorld(devCommands);
        Playground.installListeners(world);
        return world;
    }

    /** Everything else, once the games are in: an NPC stands for each mode. */
    public static void install(@NotNull InstanceContainer main) {
        Lobbies.install(main); // the main lobby, then one for Bed Wars and one for duels
        Matches.home(player -> Lobbies.send(player, Lobbies.home(player)));
        Duels.picker(PlayerMenu::duel);
        Welcome.install(); // the first lines an arrival reads
        // a lobby reader's own setting says whose lines reach them
        Rooms.hears((speaker, reader) -> Lobbies.in(speaker) == null || Settings.LOBBY_CHAT.of(reader).admits(speaker, reader));
        // the chrome follows the viewer's game look (Rooms); the first send lands before Via reveals the protocol
        MinecraftServer.getGlobalEventHandler().addListener(PlayerSpawnEvent.class, e -> {
            if (e.isFirstSpawn()) Msg.onceProtocolKnown(e.getPlayer(), 60, () -> TabList.refresh(e.getPlayer()));
        });
        Lobby.install(); // the lobby sidebar - game boards sit on top of it
        Settings.install();
        Hotbar.install(); // the game menu, the menu, the players dye, the settings, the leave dye
        Counts.install(); // the lobby's numbers repaint on a launch, a seat, a phase, an end, the queue
        Npcs.install(); // an NPC per kind of game a lobby hosts
        QueueLine.install();
    }
}
