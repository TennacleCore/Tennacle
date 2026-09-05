package tennacle.games.bridge;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.barnacle.Arena;
import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.bloom.Session;
import io.github.term4.canvas.theme.Livery;
import io.github.term4.canvas.theme.Tint;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A map's arms take their team's look at launch, written into the SHARD - the arena on disk never changes, so
 * a seasonal palette reaches every map without one being re-authored.
 */
class SectionsTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    /** A deck along x, 1 west and 2 east, long enough that the arm ends sit clear of any cage. */
    static Arena fixture() {
        Session s = Session.create("sections-fixture", new ShardManager(), p -> {});
        try {
            for (int x = -16; x <= 16; x++) s.instance().setBlock(x, 64, 0, Block.WHITE_TERRACOTTA);
            s.instance().setBlock(-15, 64, 0, Block.STONE);
            s.addMarker(new RegionMarker("center", "", "", new BlockVec(0, 64, 0), new BlockVec(0, 64, 0)));
            s.addMarker(new RegionMarker("section", "1", "", new BlockVec(-16, 64, 0), new BlockVec(-1, 64, 0)));
            s.addMarker(new RegionMarker("section", "2", "", new BlockVec(1, 64, 0), new BlockVec(16, 64, 0)));
            s.addMarker(new PosMarker("bridge:cage", "1", "", new Pos(-4.5, 65, 0.5, -90, 0)));
            s.addMarker(new PosMarker("bridge:cage", "2", "", new Pos(5.5, 65, 0.5, 90, 0)));
            s.addMarker(new PosMarker("bridge:respawn", "1", "", new Pos(-4.5, 65, 0.5, -90, 0)));
            s.addMarker(new PosMarker("bridge:respawn", "2", "", new Pos(5.5, 65, 0.5, 90, 0)));
            s.addMarker(new RegionMarker("bridge:goal", "1", "", new BlockVec(-16, 64, -1), new BlockVec(-15, 66, 1)));
            s.addMarker(new RegionMarker("bridge:goal", "2", "", new BlockVec(15, 64, -1), new BlockVec(16, 66, 1)));
            s.addMarker(new RegionMarker("bridge:build", "", "", new BlockVec(-16, 65, -2), new BlockVec(16, 65, 2)));
            s.addMarker(new LimitMarker("bridge:height", "pinned", "", null, 72.0, null));
            s.addMarker(new LimitMarker("bridge:void", "", "", null, 54.0, null));
            return Arena.read(s.save());
        } finally {
            s.close();
        }
    }

    private static Game stamped(Arena map) {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(bridge, null), map);
        for (int i = 0; i < 3; i++) MinecraftServer.getSchedulerManager().processTick();
        return game;
    }

    @Test
    void eachArmTakesItsTeamsHueAndTheArenaKeepsItsOwn() {
        Arena map = fixture();
        Game game = stamped(map);
        try {
            assertEquals(Block.BLUE_TERRACOTTA, game.shard().getBlock(-16, 64, 0), "team 1's arm");
            assertEquals(Block.RED_TERRACOTTA, game.shard().getBlock(16, 64, 0), "team 2's arm");
            assertEquals(Block.WHITE_TERRACOTTA, game.shard().getBlock(0, 64, 0), "the center is nobody's section");
            assertEquals(Block.STONE, game.shard().getBlock(-15, 64, 0), "in no dyed family: left as built");

            var built = map.instantiate();
            assertEquals(Block.WHITE_TERRACOTTA, built.getBlock(-16, 64, 0), "the arena itself is never written");
        } finally {
            game.end(Outcome.aborted());
        }
    }

    @Test
    void aSeasonalPaletteReachesAMapNobodyReauthored() {
        List<Tint> before = Livery.ordinals();
        Livery.ordinals(List.of(Tint.parse("dark_purple"), Tint.parse("gold")));
        Game game = null;
        try {
            game = stamped(fixture());
            assertEquals(Block.PURPLE_TERRACOTTA, game.shard().getBlock(-16, 64, 0));
            assertEquals(Block.ORANGE_TERRACOTTA, game.shard().getBlock(16, 64, 0), "gold pairs with the orange dye");
        } finally {
            if (game != null) game.end(Outcome.aborted());
            Livery.ordinals(before);
        }
    }
}
