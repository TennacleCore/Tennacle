package tennacle.games.bridge;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.network.packet.server.play.MultiBlockChangePacket;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reported from duels: a player could not see the blocks another player placed or broke. Both sit in the same
 * game shard, so a write by one has to reach the other's client.
 */
class PeerSeesBlocksTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void aPeerSeesWhatTheOtherBuilds() {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "PeerBlockRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "PeerBlockBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertEquals(true, game.join(red.player));
            assertEquals(true, game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            BridgeRoundTest.tickUntil(game, "GO", () -> Match.live(game));

            BlockVec at = new BlockVec(-3, 66, 0);
            Point key = new Vec(-3, 66, 0); // the wire reader keys by Vec; BlockVec never equals it
            red.sent.clear();
            blue.sent.clear();
            game.shard().setBlock(at, Block.WHITE_TERRACOTTA);

            assertEquals(Block.WHITE_TERRACOTTA.stateId(), statesSentTo(blue).get(key),
                    "the other seat is told: " + statesSentTo(blue));
            assertEquals(Block.WHITE_TERRACOTTA.stateId(), statesSentTo(red).get(key),
                    "and so is the builder");

            red.sent.clear();
            blue.sent.clear();
            game.shard().setBlock(at, Block.AIR);
            assertEquals(Block.AIR.stateId(), statesSentTo(blue).get(key),
                    "and the break too: " + statesSentTo(blue));
        } finally {
            game.end(Outcome.draw());
            red.player.remove();
            blue.player.remove();
        }
    }

    // Archipelago's BlockWire lives in its test sources, which this module cannot see
    private static Map<Point, Integer> statesSentTo(FakePlayer player) {
        Map<Point, Integer> out = new LinkedHashMap<>();
        for (var packet : player.sent) {
            if (packet instanceof BlockChangePacket b) {
                out.put(new Vec(b.blockPosition().blockX(), b.blockPosition().blockY(), b.blockPosition().blockZ()),
                        b.blockStateId());
            } else if (packet instanceof MultiBlockChangePacket m) {
                long section = m.chunkSectionPosition();
                int sx = (int) (section >> 42), sy = (int) (section << 44 >> 44), sz = (int) (section << 22 >> 42);
                for (long entry : m.blocks()) {
                    int index = (int) (entry & 0xFFF);
                    out.put(new Vec((sx << 4) + ((index >> 8) & 0xF), (sy << 4) + (index & 0xF),
                            (sz << 4) + ((index >> 4) & 0xF)), (int) (entry >>> 12));
                }
            }
        }
        return out;
    }
}
