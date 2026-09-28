package tennacle.games.bedwars;

import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;
import tennacle.games.beds.Beds;
import tennacle.games.beds.Lives;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.barnacle.Arena;
import io.github.term4.barnacle.marker.DataMarker;
import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.PointMarker;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.bloom.mapmaking.Session;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.entity.DroppedItemEntity;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.codec.Codec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.SetTitleTextPacket;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A game fills in the room and opens at the count; a bed gone makes the next death the last, and the block ends it. */
class BedWarsRoundTest extends HeadlessServerTest {

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path replays;

    private static final Pos ROOM = new Pos(0.5, 100, 0.5);

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        tennacle.glue.ReplayBridge.install();
        tennacle.games.watch.Replays.install(replays, new ShardManager());
    }

    /** Three sides round a wool deck, a glass room in the sky, the limits and a radius of 30. */
    static Arena fixture() {
        Session s = Session.create("bedwars-fixture", new ShardManager(), p -> {});
        try {
            for (int x = -20; x <= 20; x++) {
                for (int z = -20; z <= 20; z++) s.instance().setBlock(x, 64, z, Block.WHITE_WOOL);
            }
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) s.instance().setBlock(x, 99, z, Block.GLASS);
            }
            bed(s, -11, -10, 0, 0, "east", Block.RED_BED, "1");
            bed(s, 11, 10, 0, 0, "west", Block.BLUE_BED, "2");
            bed(s, 0, 0, 11, 10, "north", Block.LIME_BED, "3");
            s.addMarker(new PosMarker("grotto:spawn", "1", "", new Pos(-7.5, 65, 0.5, -90, 0)));
            s.addMarker(new PosMarker("grotto:spawn", "2", "", new Pos(7.5, 65, 0.5, 90, 0)));
            s.addMarker(new PosMarker("grotto:spawn", "3", "", new Pos(0.5, 65, 7.5, 180, 0)));
            s.addMarker(new RegionMarker("bedwars:base", "1", "", new BlockVec(-13, 64, -2), new BlockVec(-9, 68, 2)));
            s.addMarker(new RegionMarker("bedwars:base", "2", "", new BlockVec(9, 64, -2), new BlockVec(13, 68, 2)));
            s.addMarker(new RegionMarker("bedwars:base", "3", "", new BlockVec(-2, 64, 9), new BlockVec(2, 68, 13)));
            s.addMarker(new RegionMarker("bedwars:lobby", "", "", new BlockVec(-2, 99, -2), new BlockVec(2, 99, 2)));
            CompoundBinaryTag spawners = CompoundBinaryTag.builder().putInt("solo", 3).putInt("doubles", 3)
                    .putInt("3v3v3v3", 3).putInt("4v4v4v4", 5).build();
            s.addMarker(new PointMarker("bedwars:forge", "1", "", new Vec(-15.5, 65, 0.5)));
            s.addMarker(new PointMarker("bedwars:forge-iron", "1", "", new Vec(-15.5, 65, 0.5), spawners, 1));
            s.addMarker(new PointMarker("bedwars:forge", "2", "", new Vec(15.5, 65, 0.5)));
            s.addMarker(new PointMarker("bedwars:forge-iron", "2", "", new Vec(15.5, 65, 0.5), spawners, 1));
            s.addMarker(new PointMarker("bedwars:forge", "3", "", new Vec(0.5, 65, 15.5)));
            s.addMarker(new PointMarker("bedwars:forge-iron", "3", "", new Vec(0.5, 65, 15.5), spawners, 1));
            s.addMarker(new PointMarker("bedwars:shop", "1", "", new Vec(-12.5, 65, 4.5)));
            s.addMarker(new PointMarker("bedwars:shop", "2", "", new Vec(12.5, 65, 4.5)));
            s.addMarker(new PointMarker("bedwars:shop", "3", "", new Vec(4.5, 65, 12.5)));
            s.addMarker(new PointMarker("bedwars:diamond", "", "", new Vec(0.5, 65, -15.5)));
            s.addMarker(new PointMarker("bedwars:emerald", "", "", new Vec(-15.5, 65, -15.5)));
            s.addMarker(new LimitMarker("bedwars:height", "", "", null, 80.0, null));
            s.addMarker(new LimitMarker("bedwars:floor", "", "", null, 60.0, null));
            s.addMarker(new LimitMarker("bedwars:void", "", "", null, 50.0, null));
            s.addMarker(new DataMarker("bedwars:radius", "", "", CompoundBinaryTag.builder().putDouble("radius", 30.0).build(), 1));
            s.spawn(ROOM);
            return Arena.read(s.save());
        } finally {
            s.close();
        }
    }

    private static void bed(Session s, int footX, int headX, int footZ, int headZ, String facing, Block bed, String team) {
        s.instance().setBlock(footX, 65, footZ, bed.withProperty("facing", facing).withProperty("part", "foot"));
        s.instance().setBlock(headX, 65, headZ, bed.withProperty("facing", facing).withProperty("part", "head"));
        s.addMarker(new RegionMarker("bedwars:bed", team, "", new BlockVec(Math.min(footX, headX), 65, Math.min(footZ, headZ)),
                new BlockVec(Math.max(footX, headX), 65, Math.max(footZ, headZ))));
    }

    @Test
    void thePartyWaitsInTheRoomAndOpensTogether() {
        Mode doubles = Matches.mode("bedwars-doubles");
        Game game = Matches.launch(doubles, Matches.platformFor(null), fixture());
        game.state().set(Scope.GAME, Countdown.START_AT, 2); // a fixture of three seats, not sixteen
        game.state().set(Scope.GAME, Countdown.FROM, 2);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwPartyA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwPartyB");
        FakePlayer c = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwPartyC");
        try {
            a.player.setInstance(game.instance(), ROOM).join();
            b.player.setInstance(game.instance(), ROOM).join();
            c.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player, b.player)));
            assertTrue(Matches.enter(game, List.of(c.player)), "someone to fight");
            await("all seated", () -> Game.of(a.player) == game && Game.of(b.player) == game && Game.of(c.player) == game);
            assertEquals(Match.teamOf(game, a.player), Match.teamOf(game, b.player), "a party shares a team");
            assertNotEquals(Match.teamOf(game, a.player), Match.teamOf(game, c.player), "the next arrival takes the emptiest team");
            assertEquals(ROOM.y(), a.player.getPosition().y(), 1e-9, "held in the room");
            assertTrue(a.player.getInventory().getItemStack(0).isAir(), "no kit in the room");
            assertEquals(Block.GLASS, game.shard().getBlock(new BlockVec(0, 99, 0)));

            live(game);
            Pos seat = game.roster().slotOf(a.player).spawn();
            assertEquals(seat.y(), a.player.getPosition().y(), 1e-9, "GO opens the seat");
            assertEquals(seat.x(), a.player.getPosition().x(), 1e-9);
            assertEquals(Material.WOODEN_SWORD, a.player.getInventory().getItemStack(0).material(), "and the kit comes with it");
            assertEquals(Block.AIR, game.shard().getBlock(new BlockVec(0, 99, 0)), "the room is gone");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
            c.player.remove();
        }
    }

    @Test
    void aBrokenBedThenTheLastDeathEndsIt() {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = Matches.launch(solo, Matches.platformFor(null), fixture());
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwSoloA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwSoloB");
        try {
            a.player.setInstance(game.instance(), ROOM).join();
            b.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player)));
            assertTrue(Matches.enter(game, List.of(b.player)));
            await("both seated", () -> Game.of(a.player) == game && Game.of(b.player) == game);
            String aTeam = Match.teamOf(game, a.player), bTeam = Match.teamOf(game, b.player);
            assertNotEquals(aTeam, bTeam, "solo: one a team");
            live(game);
            assertFalse(Beds.stands(game, "3"), "the side nobody sat on lost its bed at GO");

            BlockVec bBed = game.arena().marker(BedWars.BED, bTeam).boxes().getFirst().min();
            EventDispatcher.call(new PlayerBlockBreakEvent(a.player, game.instance(), Block.BLUE_BED, Block.AIR, bBed, BlockFace.TOP));
            assertFalse(Beds.stands(game, bTeam), "the bed went");
            assertEquals(1, game.state().get(Scope.slot(game.roster().slotOf(a.player).index()), BedWars.BEDS));

            a.sent.clear();
            b.sent.clear();
            game.module(Lives.class).fell(b.player);
            assertEquals(Outcome.winner(aTeam), game.decided(), "the last death ends it");
            assertEquals(1, game.state().get(Scope.slot(game.roster().slotOf(b.player).index()), BedWars.FINAL_DEATHS));
            MinecraftServer.getSchedulerManager().processTickEnd(); // the block goes out at the end of the tick
            List<String> chat = a.sent(SystemChatPacket.class).stream()
                    .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
            assertTrue(chat.stream().anyMatch(l -> l.contains("Bed Wars") && l.contains(" - BwSoloA")
                    && l.contains("1st Killer - BwSoloA - 0")), "the block names the side and the killers: " + chat);
            assertEquals("VICTORY!", lastTitle(a), "the winner's title");
            assertEquals("GAME OVER!", lastTitle(b), "the loser's");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }

    /** The forges run from GO, the middle drops at once, and the timeline's tier-up restarts it with its line. */
    @Test
    void forgesAndTheMiddleRunOnTheClock() {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = Matches.launch(solo, Matches.platformFor(null), fixture());
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        game.state().set(Scope.GAME, StateKey.of("timeline.diamond-2", Codec.LONG, 0L), 60L);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwGenA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwGenB");
        try {
            a.player.setInstance(game.instance(), ROOM).join();
            b.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player)));
            assertTrue(Matches.enter(game, List.of(b.player)));
            await("both seated", () -> Game.of(a.player) == game && Game.of(b.player) == game);
            live(game);
            Point forge = game.arena().marker(BedWars.FORGE, Match.teamOf(game, a.player)).point();
            Point diamond = game.arena().markers(BedWars.DIAMOND).getFirst().point();
            tickUntil(game, "the boost's first wave", () -> lying(game, forge, Material.IRON_INGOT) >= 3);
            tickUntil(game, "the first diamond", () -> lying(game, diamond, Material.DIAMOND) >= 1);
            assertEquals(1, Mid.tier(game, Mid.Kind.DIAMOND));

            a.sent.clear();
            tickUntil(game, "Diamond II", () -> Mid.tier(game, Mid.Kind.DIAMOND) == 2);
            assertEquals(2, lying(game, diamond, Material.DIAMOND), "the tier-up drops one on the spot");
            List<String> chat = a.sent(SystemChatPacket.class).stream()
                    .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
            assertTrue(chat.contains("Diamond Generators have been upgraded to Tier II"), "" + chat);
            assertTrue(lying(game, forge, Material.GOLD_INGOT) >= 1, "gold too");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }

    private static int lying(Game game, Point at, Material material) {
        return (int) game.instance().getEntities().stream()
                .filter(e -> e instanceof DroppedItemEntity item && item.getItemStack().material() == material
                        && e.getPosition().distance(at) <= 3).count();
    }

    private static String lastTitle(FakePlayer p) {
        List<String> titles = p.sent(SetTitleTextPacket.class).stream()
                .map(t -> PlainTextComponentSerializer.plainText().serialize(t.title())).filter(t -> !t.isEmpty()).toList();
        return titles.isEmpty() ? "" : titles.getLast();
    }

    static void live(Game game) {
        tickUntil(game, "the countdown ran out", () -> Match.live(game));
    }

    static void tickUntil(Game game, String what, Supplier<Boolean> done) {
        long deadline = System.currentTimeMillis() + 8000;
        while (!done.get() && System.currentTimeMillis() < deadline) {
            MinecraftServer.getSchedulerManager().processTick();
            MinecraftServer.getSchedulerManager().processTickEnd();
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertTrue(done.get(), what);
    }
}
