package tennacle.games.map;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.barnacle.marker.MarkerKey;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.bloom.mapmaking.Session;
import io.github.term4.grotto.GameType;
import io.github.term4.grotto.roster.SlotLayout;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A map still being built waits outside the rotation instead of killing the match that draws it. */
class MapPoolFitnessTest extends HeadlessServerTest {

    private static final MarkerKey<PosMarker> SPAWN = MarkerKey.pos("grotto:spawn");
    private static final MarkerKey<RegionMarker> GOAL = MarkerKey.region("pooltest:goal");

    @TempDir
    static Path arenas;

    private static void write(Path dir, String name, boolean goals) throws IOException {
        Session s = Session.create(name, new ShardManager(), p -> {});
        try {
            s.addMarker(new PosMarker("grotto:spawn", "1", "", new Pos(0.5, 65, 0.5)));
            s.addMarker(new PosMarker("grotto:spawn", "2", "", new Pos(4.5, 65, 0.5)));
            if (goals) {
                s.addMarker(new RegionMarker("pooltest:goal", "1", "", new BlockVec(0, 64, 0), new BlockVec(0, 64, 0)));
                s.addMarker(new RegionMarker("pooltest:goal", "2", "", new BlockVec(4, 64, 0), new BlockVec(4, 64, 0)));
            }
            s.tag("pooltest");
            Files.write(dir.resolve(name + ".bncl"), s.save());
        } finally {
            s.close();
        }
    }

    @Test
    void anUnfinishedMapWaitsOutsideTheRotation() throws IOException {
        write(arenas, "ready", true);
        write(arenas, "halfbuilt", false);
        String was = System.getProperty("tennacle.arena-dir");
        System.setProperty("tennacle.arena-dir", arenas.toString());
        try {
            GameType type = GameType.builder("pooltest").slots(SlotLayout.groups(SPAWN, 1))
                    .expect(SPAWN, 2).expect(GOAL, 2).build();

            MapPool pool = MapPool.of("pooltest").serves(type);
            assertEquals(java.util.Set.of("ready"), pool.names(), "only the finished map is in rotation");
            for (int i = 0; i < 20; i++) {
                assertEquals("ready", pool.get().meta().id(), "and it is the only one ever drawn");
            }
            assertFalse(pool.names().contains("halfbuilt"));

            write(arenas, "halfbuilt", true); // the markers land, as /arena save would
            MapPool after = MapPool.of("pooltest").serves(type);
            assertTrue(after.names().contains("halfbuilt"), "a finished map is back in: " + after.names());

            MapPool none = MapPool.of("nobodystag").serves(type);
            assertThrows(IllegalStateException.class, none::get, "an empty rotation still says so");

            // a mode's own check, beyond the counts: both maps have every marker and one still cannot be played
            MapPool picky = MapPool.of("pooltest").serves(type)
                    .requires(a -> a.meta().id().equals("halfbuilt") ? "a bed under a spawn group" : null);
            assertEquals(java.util.Set.of("ready"), picky.names(), "the check holds it out too");
            assertTrue(picky.holdouts().toString().contains("a bed under a spawn group"),
                    "and says what it wants: " + picky.holdouts());

            MapPool throwing = MapPool.of("pooltest").serves(type)
                    .requires(a -> { throw new IllegalStateException("no such tint 'chartreuse'"); });
            assertTrue(throwing.names().isEmpty(), "a check that throws holds the map out rather than the launch");
            assertTrue(throwing.holdouts().toString().contains("chartreuse"), "" + throwing.holdouts());
        } finally {
            if (was == null) System.clearProperty("tennacle.arena-dir");
            else System.setProperty("tennacle.arena-dir", was);
        }
    }
}
