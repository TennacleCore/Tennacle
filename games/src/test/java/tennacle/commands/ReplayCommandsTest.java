package tennacle.commands;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.Test;
import tennacle.access.Access;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import tennacle.games.watch.ReplayCommands;
class ReplayCommandsTest extends HeadlessServerTest {

    /** Without the staff node, a player's /replay is the watch alone: no seeking someone else's theater, no commits. */
    @Test
    void playersWatchOnly() {
        ReplayCommands.register(new ShardManager(), instance);
        Command replay = MinecraftServer.getCommandManager().getCommand("replay");
        assertNotNull(replay);
        FakePlayer p = FakePlayer.connect(instance, new Pos(700.5, 65, 700.5), "RcWatch");
        Access.everyone(Set.of()); // TestWiring hands the whole suite the staff tools
        try {
            long open = replay.getSyntaxes().stream()
                    .filter(s -> s.getCommandCondition() == null || s.getCommandCondition().canUse(p.player, null)).count();
            assertEquals(1, open, "watch alone");
        } finally {
            Access.everyone(Set.of("tennacle.staff.*"));
            p.player.remove();
        }
    }
}
