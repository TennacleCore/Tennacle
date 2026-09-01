package tennacle.games;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.MechanicsBoot;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.InstanceContainer;
import tennacle.games.sumo.Sumo;

/** One wiring for every game test in the shared headless server - installs are not re-runnable. */
public final class TestWiring {

    private static boolean wired;

    private TestWiring() {}

    public static synchronized void ensure(InstanceContainer lobby) {
        if (wired) return;
        wired = true;
        tennacle.Mechanics.polyp = MechanicsBoot.ensure();
        Duels.install(lobby, new Pos(0.5, 65, 0.5), new ShardManager());
        tennacle.GameIds.install(Duels.grotto(), "tennacle-dev-secret");
        Sumo.install();
        tennacle.games.boxing.Boxing.install();
        tennacle.games.bridge.Bridge.install();
        tennacle.commands.LobbyCommands.register();
    }
}
