package tennacle.server;

import io.github.term4.polyp.Polyp;
import net.minestom.server.instance.InstanceContainer;
import tennacle.dev.DevCommands;
import tennacle.lobby.LobbyWiring;

/** Every module's wiring plus the dev tools, for the tests that drive the whole server. */
public final class ServerWiring {

    private static boolean wired;

    private ServerWiring() {}

    public static synchronized void ensure(InstanceContainer lobby) {
        LobbyWiring.ensure(lobby);
        if (wired) return;
        wired = true;
        DevCommands.register(Polyp.getInstance()); // /gm from a spectator switches bodies
    }
}
