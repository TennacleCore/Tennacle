package tennacle.lobby;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.matchmaking.Queues;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /party warp}: the party comes to the leader's lobby, and a member in a game only on a second ask. */
class PartyWarpTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        LobbyWiring.ensure(instance);
    }

    private static void run(FakePlayer p, String command) {
        MinecraftServer.getCommandManager().execute(p.player, command);
    }

    private static List<String> plain(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .<String>map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }

    private static Game awaitGame(FakePlayer p) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (Game.of(p.player) == null && System.currentTimeMillis() < deadline) {
            MinecraftServer.getSchedulerManager().processTick();
            Thread.sleep(10);
        }
        return Game.of(p.player);
    }

    // an instance change may wait on its chunks
    private static void arrive(FakePlayer p) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (p.player.getInstance() != instance && System.currentTimeMillis() < deadline) {
            MinecraftServer.getSchedulerManager().processTick();
            Thread.sleep(10);
        }
    }

    @Test
    void warpBringsTheParty() throws InterruptedException {
        InstanceContainer away = MinecraftServer.getInstanceManager().createInstanceContainer();
        away.setGenerator(unit -> unit.modifier().fillHeight(0, 64, Block.STONE));
        FakePlayer lead = FakePlayer.connect(instance, new Pos(70.5, 65, 0.5), "WarpLead");
        FakePlayer mate = FakePlayer.connect(away, new Pos(0.5, 65, 0.5), "WarpMate");
        try {
            run(lead, "party WarpMate");
            run(mate, "party join WarpLead");
            run(lead, "party warp");
            arrive(mate);
            assertSame(instance, mate.player.getInstance(), "in the leader's lobby");
            assertTrue(plain(mate).contains("Party Leader, WarpLead, summoned you to their server."), "" + plain(mate));
            assertTrue(plain(lead).contains("You summoned WarpMate to your server."), "" + plain(lead));
        } finally {
            run(lead, "party disband");
            lead.player.remove();
            mate.player.remove();
            MinecraftServer.getInstanceManager().unregisterInstance(away);
        }
    }

    @Test
    void aMateInAGameAsksTwice() throws InterruptedException {
        FakePlayer lead = FakePlayer.connect(instance, new Pos(74.5, 65, 0.5), "WarpBoss");
        FakePlayer mate = FakePlayer.connect(instance, new Pos(76.5, 65, 0.5), "WarpBusy");
        FakePlayer foe = FakePlayer.connect(instance, new Pos(78.5, 65, 0.5), "WarpFoe");
        Game game = null;
        try {
            run(mate, "play duels_sumo_duel");
            run(foe, "play duels_sumo_duel");
            game = awaitGame(mate);
            assertNotNull(game);
            run(lead, "party WarpBusy");
            run(mate, "party join WarpBoss");
            lead.sent.clear();
            run(lead, "party warp");
            assertTrue(plain(lead).contains("WarpBusy is in a game. Type /p warp again within 10 seconds to warp them anyway."),
                    "" + plain(lead));
            assertSame(game, Game.of(mate.player), "still playing");
            run(lead, "party warp");
            assertNull(Game.of(mate.player), "out of the game");
            arrive(mate);
            assertSame(instance, mate.player.getInstance(), "and in the leader's lobby");
        } finally {
            if (game != null && game.status() == Game.Status.OPEN) game.end(Outcome.draw());
            run(lead, "party disband");
            for (FakePlayer p : List.of(lead, mate, foe)) {
                Queues.dequeue(p.player);
                p.player.remove();
            }
        }
    }
}
