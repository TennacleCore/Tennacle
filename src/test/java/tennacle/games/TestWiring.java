package tennacle.games;

import tennacle.games.sumo.Sumo;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.MechanicsBoot;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.InstanceContainer;

/** One wiring for every game test in the shared headless server - installs are not re-runnable. */
public final class TestWiring {

    private static boolean wired;

    private TestWiring() {}

    public static synchronized void ensure(InstanceContainer lobby) {
        if (wired) return;
        wired = true;
        tennacle.Mechanics.polyp = MechanicsBoot.ensure();
        io.github.term4.archipelago.shard.ShardTabList.install(); // the server's tab list: spawn/entry order is tested
        Matches.install(lobby, new Pos(0.5, 65, 0.5), new ShardManager());
        Duels.install();
        Parties.install();
        Queues.install();
        tennacle.commands.SpectateCommands.register();
        tennacle.commands.DevCommands.register(io.github.term4.polyp.Polyp.getInstance()); // /gm from a spectator switches bodies
        Spectators.install();
        tennacle.Rooms.install();
        tennacle.commands.RulesCommands.register();
        TabStats.register();
        GameSounds.register();
        tennacle.GameIds.install(Matches.grotto(), "tennacle-dev-secret");
        Sumo.install();
        tennacle.games.boxing.Boxing.install();
        tennacle.games.bridge.Bridge.install();
        tennacle.commands.LobbyCommands.register();
    }
}
