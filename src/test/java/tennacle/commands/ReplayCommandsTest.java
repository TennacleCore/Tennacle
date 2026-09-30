package tennacle.commands;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ReplayCommandsTest extends HeadlessServerTest {

    /** Off the dev build, a player's /replay is the watch alone: no seeking someone else's theater, no commits. */
    @Test
    void playersWatchOnly() {
        ReplayCommands.register(new ShardManager(), instance, false);
        Command replay = MinecraftServer.getCommandManager().getCommand("replay");
        assertNotNull(replay);
        assertEquals(1, replay.getSyntaxes().size(), "watch alone");
    }
}
