package tennacle.games.bedfight;

import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.barnacle.Arena;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.bloom.mapmaking.Session;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameType;
import io.github.term4.grotto.Markers;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.modules.flow.Phases;
import io.github.term4.grotto.modules.outcome.LastStanding;
import io.github.term4.grotto.roster.SlotLayout;
import io.github.term4.grotto.roster.Team;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A side nobody sat on at GO starts out, and its bed goes without a fall. */
class AbsentTeamTest extends HeadlessServerTest {

    /** Each side's bed head, by team. */
    private static final Map<String, Pos> BEDS = Map.of("1", new Pos(-6, 65, 0), "2", new Pos(6, 65, 0), "3", new Pos(0, 65, 6));

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    static Arena threeSides() {
        Session s = Session.create("absent-fixture", new ShardManager(), p -> {});
        try {
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) s.instance().setBlock(x, 64, z, Block.WHITE_WOOL);
            }
            s.instance().setBlock(-7, 65, 0, Block.RED_BED.withProperty("facing", "east").withProperty("part", "foot"));
            s.instance().setBlock(-6, 65, 0, Block.RED_BED.withProperty("facing", "east").withProperty("part", "head"));
            s.instance().setBlock(7, 65, 0, Block.BLUE_BED.withProperty("facing", "west").withProperty("part", "foot"));
            s.instance().setBlock(6, 65, 0, Block.BLUE_BED.withProperty("facing", "west").withProperty("part", "head"));
            s.instance().setBlock(0, 65, 7, Block.LIME_BED.withProperty("facing", "north").withProperty("part", "foot"));
            s.instance().setBlock(0, 65, 6, Block.LIME_BED.withProperty("facing", "north").withProperty("part", "head"));
            s.addMarker(new RegionMarker("bedfight:bed", "1", "", new BlockVec(-7, 65, 0), new BlockVec(-6, 65, 0)));
            s.addMarker(new RegionMarker("bedfight:bed", "2", "", new BlockVec(6, 65, 0), new BlockVec(7, 65, 0)));
            s.addMarker(new RegionMarker("bedfight:bed", "3", "", new BlockVec(0, 65, 6), new BlockVec(0, 65, 7)));
            s.addMarker(new PosMarker("grotto:spawn", "1", "", new Pos(-4.5, 65, 0.5, -90, 0)));
            s.addMarker(new PosMarker("grotto:spawn", "2", "", new Pos(5.5, 65, 0.5, 90, 0)));
            s.addMarker(new PosMarker("grotto:spawn", "3", "", new Pos(0.5, 65, 4.5, 180, 0)));
            return Arena.read(s.save());
        } finally {
            s.close();
        }
    }

    @Test
    void absentBedGoesQuietly() {
        GameType type = GameType.builder("absentteam").slots(SlotLayout.groups(Markers.SPAWN, 1))
                .rule(Countdown.FROM, 1).rule(Countdown.GRACE, 1L).rule(Countdown.INTERVAL, 1L)
                .modules(() -> Phases.of(Match.OPENING, Match.LIVE, Match.OVER),
                        Countdown::of,
                        () -> LastStanding.decides().requireSeatedAt(Match.LIVE, 2),
                        Beds::new)
                .build();
        Game game = Matches.grotto().launch(type, threeSides());
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "AbsA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "AbsB");
        try {
            a.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            b.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(a.player));
            assertTrue(game.join(b.player));
            await("both seated", () -> Game.of(a.player) == game && Game.of(b.player) == game);
            String absent = game.roster().teams().stream().map(Team::id)
                    .filter(id -> !id.equals(Match.teamOf(game, a.player)) && !id.equals(Match.teamOf(game, b.player)))
                    .findFirst().orElseThrow();
            assertTrue(Beds.stands(game, absent), "every bed stands through the countdown");

            long deadline = System.currentTimeMillis() + 8000;
            while (!Match.live(game) && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                MinecraftServer.getSchedulerManager().processTickEnd();
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            assertTrue(Match.live(game), "the countdown ran out");

            assertNull(game.decided(), "two sides are enough to play");
            assertFalse(Beds.stands(game, absent), "the empty side sleeps nowhere");
            assertEquals(Block.AIR, game.shard().getBlock(BEDS.get(absent)), "its bed is gone from the world");
            for (String side : BEDS.keySet()) {
                if (side.equals(absent)) continue;
                assertTrue(Beds.stands(game, side), side);
                assertTrue(game.shard().getBlock(BEDS.get(side)).name().endsWith("_bed"), side);
            }
        } finally {
            game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }
}
