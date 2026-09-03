package tennacle.games;

import io.github.term4.archipelago.shard.ShardTabList;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.polyp.platform.SharedTeam;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.TeamsPacket;
import net.minestom.server.scoreboard.Team;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tab ordering rides scoreboard teams: names stay whole, and the shared lib team lets a seat go for the stay. */
class TabTeamsTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void aSeatRidesTheGamesTeamUnderItsWholeName() throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "TeamTabAlpha");
        FakePlayer b = FakePlayer.connect(instance, new Pos(2.5, 65, 2.5), "TeamTabBravoLong"); // 16: a sort name would clip it
        Game game = null;
        try {
            SharedTeam.set(a.player, SharedTeam.Reason.NO_PUSH, true);
            Team lib = MinecraftServer.getTeamManager().getTeam("polyp_lib");
            assertNotNull(lib);
            assertTrue(lib.getMembers().contains("TeamTabAlpha"));
            Mode sumo = Matches.mode("sumo");
            assertNotNull(sumo);
            game = Matches.start(sumo, Matches.platformFor(sumo, null), List.of(List.of(a.player), List.of(b.player)));
            long deadline = System.currentTimeMillis() + 4000;
            while ((Game.of(a.player) == null || Game.of(b.player) == null) && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            assertNotNull(Game.of(b.player));
            assertNull(b.player.getTag(ShardTabList.SORT_NAME), "no sort name: the team orders the tab");
            assertEquals("TeamTabBravoLong", ShardTabList.listName(b.player), "the whole name lists");
            assertFalse(lib.getMembers().contains("TeamTabAlpha"), "suspended from the lib team while the game's team holds the slot");
            assertTrue(b.sent(TeamsPacket.class).stream().anyMatch(t -> t.action() instanceof TeamsPacket.AddEntitiesToTeamAction add
                    && add.entities().contains("TeamTabAlpha")), "the opponent's client got the team add under the real name");
            assertTrue(a.sent(TeamsPacket.class).stream().anyMatch(t -> t.action() instanceof TeamsPacket.CreateTeamAction create
                    && create.settings().collisionRule() == TeamsPacket.CollisionRule.NEVER), "the game's team carries the member's no-push");
            game.end(Outcome.draw());
            for (int i = 0; i < 3; i++) MinecraftServer.getSchedulerManager().processTick();
            assertTrue(lib.getMembers().contains("TeamTabAlpha"), "back on the lib team after the game");
        } finally {
            if (game != null && game.status() == Game.Status.OPEN) game.end(Outcome.draw());
            SharedTeam.set(a.player, SharedTeam.Reason.NO_PUSH, false);
            a.player.remove();
            b.player.remove();
        }
    }
}
