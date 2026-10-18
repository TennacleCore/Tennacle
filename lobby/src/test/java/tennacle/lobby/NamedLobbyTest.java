package tennacle.lobby;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;

/** {@code /l <lobby>}: a game's lobby by its id or an alias, {@code /l main} back to the first. */
class NamedLobbyTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        LobbyWiring.ensure(instance);
    }

    @Test
    void lobbyByAlias() {
        InstanceContainer arcade = MinecraftServer.getInstanceManager().createInstanceContainer();
        arcade.setGenerator(unit -> unit.modifier().fillHeight(0, 64, Block.STONE));
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) arcade.loadChunk(x, z).join();
        Lobbies.add("arcade", "Arcade", Material.CAKE, "", mode -> false, arcade, new Pos(0.5, 65, 0.5), List.of("ar"));
        FakePlayer p = FakePlayer.connect(instance, new Pos(84.5, 65, 0.5), "NamedL");
        try {
            org.junit.jupiter.api.Assertions.assertEquals("lobby arcade", tennacle.audit.Audit.label(arcade), "the audit names it");
            MinecraftServer.getCommandManager().execute(p.player, "l ar");
            arrive(p, arcade);
            MinecraftServer.getCommandManager().execute(p.player, "l");
            arrive(p, instance); // a bare /l from a game's lobby steps up to the main one
            MinecraftServer.getCommandManager().execute(p.player, "l arcade");
            arrive(p, arcade);
            MinecraftServer.getCommandManager().execute(p.player, "lobby main");
            arrive(p, instance);
            MinecraftServer.getCommandManager().execute(p.player, "l nowhere");
            assertSame(instance, p.player.getInstance(), "an unknown lobby goes nowhere");
        } finally {
            p.player.remove();
        }
    }

    // a world change lands on a later tick, and the harness runs none of its own
    private static void arrive(FakePlayer p, InstanceContainer world) {
        long deadline = System.currentTimeMillis() + 5000;
        while (p.player.getInstance() != world && System.currentTimeMillis() < deadline) {
            MinecraftServer.getSchedulerManager().processTick();
            MinecraftServer.getSchedulerManager().processTickEnd();
        }
        assertSame(world, p.player.getInstance());
    }
}
