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
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import tennacle.games.map.Building;
import tennacle.boot.Mechanics;
import io.github.term4.polyp.mechanics.explosion.ExplosionSystem;
import tennacle.games.Idle;
import io.github.term4.polyp.api.event.item.ItemPickupEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import tennacle.games.watch.Replays;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.SetTitleTextPacket;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import tennacle.games.matchmaking.Parties;
import io.github.term4.canvas.board.Boards;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.item.ItemStack;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.term4.polyp.mechanics.projectile.ProjectileSystem;
import io.github.term4.polyp.mechanics.projectile.ProjectileSnapshot;
import io.github.term4.polyp.mechanics.projectile.types.Fireball;
import io.github.term4.polyp.presets.vanilla18.Vanilla18;
import io.github.term4.polyp.mechanics.damage.DamageSnapshot;
import io.github.term4.polyp.mechanics.damage.types.explosion.ExplosionDamage;
import net.minestom.server.entity.Entity;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;

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
            s.addMarker(new RegionMarker("bedwars:lobby", "", "", new BlockVec(-12, 97, -12), new BlockVec(12, 106, 12)));
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
            s.addMarker(new PointMarker("bedwars:upgrades", "1", "", new Vec(-12.5, 65, -4.5)));
            s.addMarker(new PointMarker("bedwars:upgrades", "2", "", new Vec(12.5, 65, -4.5)));
            s.addMarker(new PointMarker("bedwars:upgrades", "3", "", new Vec(-4.5, 65, 12.5)));
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
            assertFalse(game.shard().getBlock(new BlockVec(0, 99, 0)).air(), "the room's floor under them");

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

    @Test
    void aBedlessLeaverIsTheLastDeath() {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = Matches.launch(solo, Matches.platformFor(null), fixture());
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwLeftA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwLeftB");
        try {
            a.player.setInstance(game.instance(), ROOM).join();
            b.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player)));
            assertTrue(Matches.enter(game, List.of(b.player)));
            await("both seated", () -> Game.of(a.player) == game && Game.of(b.player) == game);
            String aTeam = Match.teamOf(game, a.player), bTeam = Match.teamOf(game, b.player);
            live(game);
            BlockVec bBed = game.arena().marker(BedWars.BED, bTeam).boxes().getFirst().min();
            EventDispatcher.call(new PlayerBlockBreakEvent(a.player, game.instance(), Block.BLUE_BED, Block.AIR, bBed, BlockFace.TOP));
            assertFalse(Beds.stands(game, bTeam));
            MinecraftServer.getCommandManager().execute(b.player, "l");
            tickUntil(game, "b out", () -> Game.of(b.player) == null);
            assertEquals(Outcome.winner(aTeam), game.decided(), "nothing to come back to: the leave is the last death");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void aHeldSeatFallsWithItsBed() {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = Matches.launch(solo, Matches.platformFor(null), fixture());
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwHeldA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwHeldB");
        try {
            a.player.setInstance(game.instance(), ROOM).join();
            b.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player)));
            assertTrue(Matches.enter(game, List.of(b.player)));
            await("both seated", () -> Game.of(a.player) == game && Game.of(b.player) == game);
            String aTeam = Match.teamOf(game, a.player), bTeam = Match.teamOf(game, b.player);
            live(game);
            MinecraftServer.getCommandManager().execute(b.player, "l");
            tickUntil(game, "b out", () -> Game.of(b.player) == null);
            assertNull(game.decided(), "a seat with a bed is held");
            BlockVec bBed = game.arena().marker(BedWars.BED, bTeam).boxes().getFirst().min();
            EventDispatcher.call(new PlayerBlockBreakEvent(a.player, game.instance(), Block.BLUE_BED, Block.AIR, bBed, BlockFace.TOP));
            assertFalse(Beds.stands(game, bTeam));
            assertEquals(Outcome.winner(aTeam), game.decided(), "the bed falling on an empty seat ends it");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void aBlastTakesPlacedBlocksNotTheMap() {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = Matches.launch(solo, Matches.platformFor(null), fixture());
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwBlastA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwBlastB");
        try {
            a.player.setInstance(game.instance(), ROOM).join();
            b.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player)));
            assertTrue(Matches.enter(game, List.of(b.player)));
            await("both seated", () -> Game.of(a.player) == game && Game.of(b.player) == game);
            live(game);
            BlockVec cell = new BlockVec(0, 65, -5);
            assertTrue(Building.of(game).place(a.player, cell, Block.WHITE_WOOL), "a member's wool");
            new ExplosionSystem(Mechanics.polyp, null).explode(game.shard(), new Pos(0.5, 65.5, -5.5), 2.0f, null, null);
            assertEquals(Block.AIR, game.shard().getBlock(cell), "the placed wool went");
            assertEquals(Block.WHITE_WOOL, game.shard().getBlock(new BlockVec(0, 64, -5)), "the map's floor stays");
            assertFalse(Building.of(game).placed(cell), "and the mark went with it");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void theRoomIsLaidFromItsOwnArena() {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = Matches.launch(solo, Matches.platformFor(null), fixture());
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwRoomA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwRoomB");
        try {
            RegionMarker.Cuboid room = game.arena().marker(BedWars.LOBBY, "").boxes().getFirst();
            assertTrue(blocksIn(game, room) > 25, "the room stands while the game fills");
            a.player.setInstance(game.instance(), ROOM).join();
            b.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player)));
            assertTrue(Matches.enter(game, List.of(b.player)));
            await("both seated", () -> Game.of(a.player) == game && Game.of(b.player) == game);
            live(game);
            assertEquals(0, blocksIn(game, room), "and goes at GO");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }

    private static int blocksIn(Game game, RegionMarker.Cuboid box) {
        int n = 0;
        for (int x = box.min().blockX(); x <= box.max().blockX(); x++) {
            for (int y = box.min().blockY(); y <= box.max().blockY(); y++) {
                for (int z = box.min().blockZ(); z <= box.max().blockZ(); z++) {
                    if (!game.shard().getBlock(x, y, z).air()) n++;
                }
            }
        }
        return n;
    }

    @Test
    void aFireballKillIsTheThrowers() {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = Matches.launch(solo, Matches.platformFor(null), fixture());
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwFireA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwFireB");
        Entity fireball = null;
        try {
            a.player.setInstance(game.instance(), ROOM).join();
            b.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player)));
            assertTrue(Matches.enter(game, List.of(b.player)));
            await("both seated", () -> Game.of(a.player) == game && Game.of(b.player) == game);
            live(game);
            List<Lives.Death> deaths = new ArrayList<>();
            game.module(Lives.class).onDeath(deaths::add);
            ProjectileSystem projectiles = new ProjectileSystem(Mechanics.polyp, Vanilla18.projectiles());
            fireball = projectiles.launch(ProjectileSnapshot.of(a.player, Fireball.INSTANCE).withConfig(Vanilla18.projectiles()));
            assertNotNull(fireball, "the fireball flies");
            Mechanics.polyp.services().damage().apply(DamageSnapshot.of(b.player, ExplosionDamage.INSTANCE).withSource(fireball).withAmount(100f));
            assertEquals(1, deaths.size(), "one death");
            assertSame(a.player, deaths.getFirst().killer(), "the thrower's kill");
            assertNull(deaths.getFirst().tool(), "named with no tool");
        } finally {
            if (fireball != null) fireball.remove();
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

    @Test
    void theWaitLetsNothingBreakOrBuild() {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = Matches.launch(solo, Matches.platformFor(null), fixture());
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwHoldA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwHoldB");
        try {
            a.player.setInstance(game.instance(), ROOM).join();
            b.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player)));
            assertTrue(Matches.enter(game, List.of(b.player)));
            await("both seated", () -> Game.of(a.player) == game && Game.of(b.player) == game);
            assertTrue(Match.opening(game));
            a.player.teleport(new Pos(11.5, 66, 2.5)).join(); // by the second side's bed, in a modern client's reach
            BlockVec foot = new BlockVec(11, 65, 0);
            var bed = new PlayerBlockBreakEvent(a.player, game.instance(), game.shard().getBlock(foot), Block.AIR, foot, BlockFace.TOP);
            EventDispatcher.call(bed);
            assertTrue(bed.isCancelled(), "no bed goes before the start");
            assertTrue(Beds.stands(game, "2"));
            a.player.setItemInMainHand(ItemStack.of(Material.WHITE_WOOL, 8));
            var wool = new PlayerBlockPlaceEvent(a.player, game.instance(), Block.WHITE_WOOL, BlockFace.TOP, new BlockVec(11, 66, 2),
                    new Pos(0.5, 1, 0.5), PlayerHand.MAIN);
            EventDispatcher.call(wool);
            assertTrue(wool.isCancelled(), "nor a block placed");
            live(game);
            a.player.teleport(new Pos(11.5, 66, 2.5)).join();
            var again = new PlayerBlockBreakEvent(a.player, game.instance(), game.shard().getBlock(foot), Block.AIR, foot, BlockFace.TOP);
            EventDispatcher.call(again);
            assertFalse(Beds.stands(game, "2"), "in play, an enemy bed goes");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void theWaitSaysWhoComesAndGoes() {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = Matches.launch(solo, Matches.platformFor(null), fixture());
        game.state().set(Scope.GAME, Countdown.START_AT, 7); // never enough to start
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwGoA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwGoB");
        try {
            a.player.setInstance(game.instance(), ROOM).join();
            b.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player)));
            await("a seated", () -> Game.of(a.player) == game);
            a.sent.clear();
            assertTrue(Matches.enter(game, List.of(b.player)));
            await("b seated", () -> Game.of(b.player) == game);
            assertTrue(plain(a).contains("BwGoB has joined (2/3)!"), plain(a).toString());
            tickUntil(game, "both boards count two: " + board(a) + " / " + board(b), () -> board(a).contains("Players: 2/3") && board(b).contains("Players: 2/3"));
            a.sent.clear();
            b.sent.clear();
            MinecraftServer.getCommandManager().execute(b.player, "l");
            await("b out", () -> Game.of(b.player) == null);
            assertTrue(plain(b).contains("You left Bed Wars Solo."), plain(b).toString());
            assertFalse(plain(b).stream().anyMatch(l -> l.contains("rejoin")), "nothing to come back to: " + plain(b));
            assertTrue(plain(a).contains("BwGoB has quit!"), plain(a).toString());
            assertNull(game.roster().reservedSlotOf(b.player.getUsername()), "the seat is free, not held");
            tickUntil(game, "the board counts one again", () -> board(a).contains("Players: 1/3"));
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void aPartyLeavesTheWaitTogether() {
        Mode doubles = Matches.mode("bedwars-doubles");
        Game game = Matches.launch(doubles, Matches.platformFor(null), fixture());
        game.state().set(Scope.GAME, Countdown.START_AT, 7);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwPtyA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwPtyB");
        FakePlayer c = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwPtyC");
        try {
            MinecraftServer.getCommandManager().execute(a.player, "party BwPtyB");
            MinecraftServer.getCommandManager().execute(b.player, "party join BwPtyA");
            assertEquals(2, Parties.of(a.player).size(), "a party of two");
            a.player.setInstance(game.instance(), ROOM).join();
            b.player.setInstance(game.instance(), ROOM).join();
            c.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player, b.player)));
            assertTrue(Matches.enter(game, List.of(c.player)));
            await("all seated", () -> Game.of(a.player) == game && Game.of(b.player) == game && Game.of(c.player) == game);
            b.sent.clear();
            MinecraftServer.getCommandManager().execute(a.player, "l");
            await("the party is out", () -> Game.of(a.player) == null && Game.of(b.player) == null);
            assertTrue(plain(b).contains("Your party left the game."), plain(b).toString());
            assertSame(game, Game.of(c.player), "the other side stays");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            MinecraftServer.getCommandManager().execute(b.player, "party leave");
            a.player.remove();
            b.player.remove();
            c.player.remove();
        }
    }

    @Test
    void standingStillStopsPickups() {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = Matches.launch(solo, Matches.platformFor(null), fixture());
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        game.state().set(Scope.GAME, Idle.PICKUP, 40L);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwIdleA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwIdleB");
        try {
            a.player.setInstance(game.instance(), ROOM).join();
            b.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player)));
            assertTrue(Matches.enter(game, List.of(b.player)));
            await("both seated", () -> Game.of(a.player) == game && Game.of(b.player) == game);
            live(game);
            DroppedItemEntity iron = DroppedItemEntity.spawn(game.shard(), a.player.getPosition(), Vec.ZERO, ItemStack.of(Material.IRON_INGOT), null, 0);
            assertNotNull(iron);
            long from = game.tick();
            tickUntil(game, "forty ticks still", () -> game.tick() >= from + 41);
            var late = new ItemPickupEvent(a.player, iron);
            EventDispatcher.call(late);
            assertTrue(late.isCancelled(), "no pickup for a member standing still");
            EventDispatcher.call(new PlayerMoveEvent(a.player, a.player.getPosition().add(0.5, 0, 0), true));
            var moved = new ItemPickupEvent(a.player, iron);
            EventDispatcher.call(moved);
            assertFalse(moved.isCancelled(), "a step brings the pickups back");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void theConvertedMapsCarryCornerStairs() {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = Matches.launch(solo, Matches.platformFor(null), solo.arena().named("bedwars-dragonstar"));
        try {
            int corners = 0;
            assertFalse(game.instance().getChunks().isEmpty(), "the map's chunks are loaded");
            for (Chunk chunk : game.instance().getChunks()) {
                for (Section section : chunk.getSections()) {
                    int[] found = {0};
                    section.blockPalette().getAllPresent((x, y, z, value) -> {
                        Block block = Block.fromStateId(value);
                        String shape = block != null ? block.getProperty("shape") : null;
                        if (shape != null && !shape.equals("straight") && block.key().value().endsWith("_stairs")) found[0]++;
                    });
                    corners += found[0];
                }
            }
            assertTrue(corners > 0, "Dragonstar's stairs never turn a corner");
        } finally {
            Replays.discard(game);
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
        }
    }

    @Test
    void aWaitingGameIsFoundByItsMap() {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = Matches.launch(solo, Matches.platformFor(null), fixture());
        try {
            assertSame(game, Matches.waiting(solo, Matches.platformFor(null)));
            assertSame(game, Matches.waiting(solo, Matches.platformFor(null), game.arena().meta().name()));
            assertNull(Matches.waiting(solo, Matches.platformFor(null), "nowhere"));
            assertEquals("bedwars-dragonstar", Matches.mapNamed(solo, "dragonstar"), "a map goes by its name without the pool's prefix");
            assertEquals("bedwars-dragonstar", Matches.mapNamed(solo, "BEDWARS-DRAGONSTAR"));
            assertNull(Matches.mapNamed(solo, "atlantis"));
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
        }
    }

    private static List<String> plain(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }

    /** The sidebar as {@code p} last saw it, a line a string. */
    private static List<String> board(FakePlayer p) {
        Boards.Rendered rendered = Boards.rendered(p.player);
        if (rendered == null) return List.of();
        return rendered.lines().stream().map(line -> PlainTextComponentSerializer.plainText().serialize(line).strip()).toList(); // the board pads its width
    }
}
