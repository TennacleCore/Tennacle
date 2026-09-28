package tennacle.games;

import tennacle.games.matchmaking.Parties;
import tennacle.games.matchmaking.Duels;
import tennacle.games.matchmaking.Queues;
import tennacle.games.watch.Spectators;
import tennacle.looks.GameSounds;
import tennacle.looks.TabStats;
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
        tennacle.boot.Mechanics.polyp = MechanicsBoot.ensure();
        // the harness boots mechanics without the systems Mechanics.install() adds
        tennacle.boot.Mechanics.containers = io.github.term4.polyp.mechanics.containers.ContainerSystem.install(
                tennacle.boot.Mechanics.polyp, io.github.term4.polyp.presets.vanilla18.Containers.config());
        tennacle.boot.Mechanics.mobs = io.github.term4.polyp.mechanics.mobs.MobsSystem.install(
                tennacle.boot.Mechanics.polyp, io.github.term4.polyp.presets.vanilla18.Mobs.config());
        tennacle.boot.Mechanics.fluids = io.github.term4.polyp.mechanics.fluids.FluidSystem.install(
                tennacle.boot.Mechanics.polyp, io.github.term4.polyp.presets.vanilla18.Fluids.config());
        tennacle.boot.Mechanics.attribution();
        io.github.term4.archipelago.shard.ShardTabList.install(); // the server's tab list: spawn/entry order is tested
        io.github.term4.polyp.platform.player.PlayerNames.visible(io.github.term4.archipelago.shard.ShardTabList::listed);
        ShardManager shards = new ShardManager();
        Matches.install(lobby, new Pos(0.5, 65, 0.5), shards);
        tennacle.audit.Audit.install(shards);
        tennacle.commands.Commands.help();
        Duels.install();
        Parties.install();
        Queues.install();
        tennacle.games.matchmaking.Play.install();
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
        tennacle.games.bedfight.BedFight.install();
        tennacle.games.bedwars.BedWars.install();
        tennacle.commands.LobbyCommands.register();
    }
}
