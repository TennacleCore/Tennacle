package tennacle.games.bridge;

import tennacle.games.Ruleset;
import tennacle.games.TestWiring;
import io.github.term4.grotto.modules.combat.Respawns;
import tennacle.boot.Mechanics;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.barnacle.Arena;
import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.bloom.mapmaking.Session;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.modules.flow.Phases;
import io.github.term4.grotto.modules.flow.TimeLimit;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.mechanics.damage.DamageSnapshot;
import io.github.term4.polyp.mechanics.damage.types.melee.MeleeDamage;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The whole round on a synthetic map: grace, cage count, GO, a goal + re-cage, a void death + respawn. */
class BridgeRoundTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        tennacle.games.TestWiring.ensure(instance);
    }

    /** Deck along x, 1 west / 2 east, goals at the far ends - every expected marker aboard. */
    static Arena fixture() {
        Session s = Session.create("bridge-fixture", new ShardManager(), p -> {});
        try {
            for (int x = -8; x <= 8; x++) s.instance().setBlock(x, 64, 0, Block.WHITE_TERRACOTTA);
            s.addMarker(new RegionMarker("center", "", "", new BlockVec(0, 64, 0), new BlockVec(0, 64, 0)));
            s.addMarker(new RegionMarker("section", "1", "", new BlockVec(-8, 64, 0), new BlockVec(-1, 64, 0)));
            s.addMarker(new RegionMarker("section", "2", "", new BlockVec(1, 64, 0), new BlockVec(8, 64, 0)));
            s.addMarker(new PosMarker("bridge:cage", "1", "", new Pos(-4.5, 65, 0.5, -90, 0)));
            s.addMarker(new PosMarker("bridge:cage", "2", "", new Pos(5.5, 65, 0.5, 90, 0)));
            // off the seats on purpose: nothing distinguishes the two markers if they share a position
            s.addMarker(new PosMarker("bridge:respawn", "1", "", new Pos(-6.5, 65, 0.5, -90, 0)));
            s.addMarker(new PosMarker("bridge:respawn", "2", "", new Pos(7.5, 65, 0.5, 90, 0)));
            s.addMarker(new RegionMarker("bridge:goal", "1", "", new BlockVec(-8, 64, -1), new BlockVec(-7, 66, 1)));
            s.addMarker(new RegionMarker("bridge:goal", "2", "", new BlockVec(7, 64, -1), new BlockVec(8, 66, 1)));
            s.addMarker(new RegionMarker("bridge:build", "", "", new BlockVec(-8, 65, -2), new BlockVec(8, 65, 2)));
            s.addMarker(new LimitMarker("bridge:height", "pinned", "", null, 72.0, null));
            s.addMarker(new LimitMarker("bridge:void", "", "", null, 54.0, null));
            byte[] bytes = s.save();
            return Arena.read(bytes);
        } finally {
            s.close();
        }
    }

    @Test
    void aFullRound() {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor("scrims"), fixture()); // the launch fold, nobody seated
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BridgeRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "BridgeBlue");
        try {
            // join from inside the arena: the synchronous branch surfaces module exceptions to the test
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player), "red seat claimed");
            assertTrue(game.join(blue.player), "blue seat claimed");
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            assertTrue(Match.opening(game), "rounds start caged");
            // no crit override any more: an arrow is critical iff drawn full, so neither knob is set
            var arrow = Mechanics.polyp.profiles().resolve(red.player,
                    io.github.term4.polyp.MechanicsKeys.PROJECTILES).typeConfig(
                    io.github.term4.polyp.mechanics.projectile.types.Arrow.KEY);
            assertEquals(Boolean.FALSE, arrow.critDamage.constantOrNull(), "scrims: the crit roll is dropped");
            assertNull(arrow.critParticles, "the crit trail follows the actual crit, never overridden");

            // a TEAM-scoped entry lands in the SAME world profile (stuckInBody proves it: off for the game, on for red)
            String redTeam = Bridge.teamOf(game, red.player);
            var reFold = Mechanics.polyp.profiles().world(game.shard()).toBuilder();
            tennacle.games.Ruleset.named("proof")
                    .path("projectiles/minecraft:arrow/stuckInBody", false)
                    .path(io.github.term4.grotto.state.Scope.team(redTeam), "projectiles/minecraft:arrow/stuckInBody", true)
                    .apply(reFold, Mechanics.polyp.profiles().global(), game);
            Mechanics.polyp.profiles().setWorld(game.shard(), reFold.build());
            java.util.function.Function<Player, Boolean> stuck = shooter -> {
                var snap = io.github.term4.polyp.mechanics.projectile.ProjectileSnapshot.of(shooter,
                        io.github.term4.polyp.mechanics.projectile.types.Arrow.INSTANCE);
                var ctx = io.github.term4.polyp.mechanics.projectile.ProjectileConfigResolver.ProjectileContext.of(snap,
                        Mechanics.polyp.services());
                var arrows = Mechanics.polyp.profiles().resolve(shooter, io.github.term4.polyp.MechanicsKeys.PROJECTILES)
                        .typeConfig(io.github.term4.polyp.mechanics.projectile.types.Arrow.KEY);
                return io.github.term4.polyp.config.FieldValue.resolve(arrows.stuckInBody, ctx, Boolean.TRUE); // unset = vanilla
            };
            assertEquals(Boolean.TRUE, stuck.apply(red.player), "team-scoped: red arrows stick");
            assertEquals(Boolean.FALSE, stuck.apply(blue.player), "blue keeps the game's value");
            assertNull(Mechanics.polyp.profiles().player(red.player), "and nothing was pushed onto a player");

            // dropping a scope is a RE-FOLD over the base, never surgery on the built profile
            var handicap = tennacle.games.Ruleset.named("proof")
                    .path(io.github.term4.grotto.state.Scope.team(redTeam), "projectiles/minecraft:arrow/stuckInBody", true);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> handicap.apply(io.github.term4.polyp.MechanicsProfile.builder(), null),
                    "a scoped entry cannot fold without the game");
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> handicap.without(io.github.term4.grotto.state.Scope.team(redTeam))
                            .apply(io.github.term4.polyp.MechanicsProfile.builder(), null));
            var typo = tennacle.games.Ruleset.named("typo")
                    .path(io.github.term4.grotto.state.Scope.team("3"), "projectiles/minecraft:arrow/stuckInBody", true);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> typo.apply(io.github.term4.polyp.MechanicsProfile.builder(), null, game),
                    "a team the roster lacks fails the fold, it does not apply to nobody");
            Matches.fold(game); // the launch fold again, without the handicap
            assertEquals(Boolean.TRUE, stuck.apply(red.player), "re-folded from the base: red is back on the bridge value");
            assertEquals(Boolean.TRUE, arrow.removeOnBlockHit.constantOrNull(), "bridge default rides along");
            await("kit applied", () -> swordOf(red.player) && swordOf(blue.player));
            // 1.8 leather chest+legs+boots = 3+2+1; if this drops to 0 nothing mitigates and TTK collapses
            assertEquals(6, (int) red.player.getAttributeValue(Attribute.ARMOR), "kit armor points");

            tickUntil(game, "GO", () -> Match.live(game));
            assertTrue(Phases.enteredAt(game, Match.LIVE) >= 0, "the clock anchored at the first beat");

            Player scorer = "2".equals(Bridge.teamOf(game, blue.player)) ? blue.player : red.player;
            // the scorer steps into the OTHER team's goal
            Pos goal = "2".equals(Bridge.teamOf(game, scorer)) ? new Pos(-7.5, 65, 0.5) : new Pos(7.5, 65, 0.5);
            EventDispatcher.call(new PlayerMoveEvent(scorer, goal, true));
            String team = Bridge.teamOf(game, scorer);
            tickUntil(game, "goal counted", () -> game.state().get(Scope.team(team), Bridge.GOALS) == 1);
            tickUntil(game, "a goal re-cages", () -> Match.opening(game));

            tickUntil(game, "GO again", () -> Match.live(game));

            // a cage is a look, not the break itself: with the blocks off a goal still reopens the count
            game.state().set(Scope.GAME, Cages.BLOCKS, false);
            EventDispatcher.call(new PlayerMoveEvent(scorer, goal, true));
            tickUntil(game, "second goal counted", () -> game.state().get(Scope.team(team), Bridge.GOALS) == 2);
            tickUntil(game, "an uncaged goal still reopens", () -> Match.opening(game));
            tickUntil(game, "GO once more", () -> Match.live(game));
            game.state().set(Scope.GAME, Cages.BLOCKS, true);

            Player faller = scorer == red.player ? blue.player : red.player;
            double home = game.arena().marker(Bridge.RESPAWN, Bridge.teamOf(game, faller)).pos().x();
            EventDispatcher.call(new PlayerMoveEvent(faller, new Pos(0.5, 50, 0.5), true));
            tickUntil(game, "void respawn", () -> Math.abs(faller.getPosition().x() - home) < 0.01
                    && faller.getPosition().y() > 60);

            // respawn protection must lapse before a hit can land
            long shieldOver = game.state().tick() + 45;
            tickUntil(game, "protection lapses", () -> game.state().tick() >= shieldOver);
            // a lethal hit cancels its FatalDamageEvent and respawns - never the death screen
            int kills = game.state().get(Scope.slot(game.roster().slotOf(scorer).index()), Bridge.KILLS);
            FakePlayer killer = scorer == red.player ? red : blue;
            killer.sent.clear();
            Mechanics.polyp.services().damage().apply(DamageSnapshot.of(faller, MeleeDamage.INSTANCE)
                    .withSource(scorer).withAmount(100f));
            assertTrue(!faller.isDead(), "a kill never reaches the death screen");
            // the respawn teleports the victim inside the fatal dispatch; the hurt sound still reaches the killer
            assertTrue(killer.sent(net.minestom.server.network.packet.server.play.SoundEffectPacket.class).stream()
                            .anyMatch(s -> s.soundEvent().key().value().contains("player.hurt")),
                    "the killer hears the killing hit");
            assertEquals(faller.getAttributeValue(Attribute.MAX_HEALTH), faller.getHealth(), "respawned refit");
            assertEquals(kills + 1, game.state().get(Scope.slot(game.roster().slotOf(scorer).index()), Bridge.KILLS),
                    "the killer is credited");

            game.decide(Outcome.draw(), 1);
        } finally {
            red.player.remove();
            blue.player.remove();
        }
    }

    @Test
    void minemenClockWaitsForGo() {
        Mode bridge = Matches.mode("bridge");
        assertNotNull(bridge);
        Game game = Matches.launch(bridge, Matches.platformFor("minemen"), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "ClockRed");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            for (int i = 0; i < 3; i++) MinecraftServer.getSchedulerManager().processTick();
            assertTrue(Match.opening(game), "still counting in");
            assertEquals(-1L, game.state().get(Scope.GAME, TimeLimit.AT), "the clock has not started");

            tickUntil(game, "GO", () -> Match.live(game));
            assertNotEquals(-1L, game.state().get(Scope.GAME, TimeLimit.AT), "it starts when the cages open");
        } finally {
            game.end(Outcome.aborted());
            red.player.remove();
        }
    }

    @Test
    void minemenOpensAtTheRespawnSpot() {
        Mode bridge = Matches.mode("bridge");
        assertNotNull(bridge);
        Game game = Matches.launch(bridge, Matches.platformFor("minemen"), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "MinemenRed");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            Slot seat = game.roster().slotOf(red.player);
            assertNotNull(seat);
            Pos respawn = game.arena().marker(Bridge.RESPAWN, seat.team()).pos();
            assertNotEquals(respawn.x(), seat.spawn().x(), "the fixture keeps the two markers apart");
            assertEquals(respawn.x(), red.player.getPosition().x(), 1e-9, "seated where minemen opens");
        } finally {
            game.end(Outcome.aborted());
            red.player.remove();
        }
    }

    @Test
    void deadWaitsGhostedThenRespawns() {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(null), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "DeadRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "DeadBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            game.state().set(Scope.GAME, Respawns.RESPAWN_TICKS, 40L);
            tickUntil(game, "GO", () -> Match.live(game));
            Respawns respawns = game.module(Respawns.class);
            red.sent.clear();
            EventDispatcher.call(new PlayerMoveEvent(red.player, new Pos(-4.5, 50, 0.5), true));
            tickUntil(game, "the wait starts", () -> respawns.isDead(red.player));
            assertTrue(game.isGhost(red.player), "the dead hover unbound at the game's dead landing");
            assertTrue(red.player.isFlying());
            assertEquals(game.deadLanding(game.roster().slotOf(red.player)).x(), red.player.getPosition().x(), 1e-6);
            assertTrue(red.sent(net.minestom.server.network.packet.server.play.SetTitleTextPacket.class).stream()
                    .anyMatch(t -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                            .serialize(t.title()).contains("You died")), "the death screen");
            tickUntil(game, "the respawn lands", () -> !respawns.isDead(red.player));
            assertFalse(game.isGhost(red.player));
            assertSame(game, Game.of(red.player), "rebound as a member");
            assertFalse(red.player.isFlying());
            game.decide(Outcome.draw(), 1);
        } finally {
            red.player.remove();
            blue.player.remove();
        }
    }

    /** The harness has no server tick loop - drive the game's instance (monotonic millis) ourselves. */
    /** A live swap of sides mid-round: the next goal counts for the scorer's new side, and the re-cage opens everyone at the cage of the base they now hold. */
    @Test
    void sidesSwapLive() {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor("scrims"), fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "SwapRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "SwapBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            tickUntil(game, "GO", () -> Match.live(game));
            String redSide = Bridge.teamOf(game, red.player);
            String blueSide = Bridge.teamOf(game, blue.player);
            game.reseat(java.util.Map.of(red.player, blueSide, blue.player, redSide));
            assertEquals(blueSide, Bridge.teamOf(game, red.player), "traded");
            assertEquals(redSide, Bridge.teamOf(game, blue.player));

            Player scorer = "2".equals(Bridge.teamOf(game, blue.player)) ? blue.player : red.player;
            Player other = scorer == red.player ? blue.player : red.player;
            String side = Bridge.teamOf(game, scorer);
            Pos goal = "2".equals(side) ? new Pos(-7.5, 65, 0.5) : new Pos(7.5, 65, 0.5);
            EventDispatcher.call(new PlayerMoveEvent(scorer, goal, true));
            tickUntil(game, "the goal counted for the new side", () -> game.state().get(Scope.team(side), Bridge.GOALS) == 1);
            tickUntil(game, "a goal re-cages", () -> Match.opening(game));
            for (Player p : java.util.List.of(scorer, other)) {
                Pos cage = game.arena().marker(Bridge.CAGE, Bridge.teamOf(game, p)).pos();
                assertEquals(cage.x(), p.getPosition().x(), 0.01, p.getUsername() + " opens at the cage of the base they now hold");
            }
            tickUntil(game, "GO again", () -> Match.live(game));
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    static void tickUntil(Game game, String what, java.util.function.Supplier<Boolean> done) {
        long deadline = System.currentTimeMillis() + 8000;
        while (!done.get() && System.currentTimeMillis() < deadline) {
            game.instance().tick(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
            net.minestom.server.MinecraftServer.getSchedulerManager().processTick();
            net.minestom.server.MinecraftServer.getSchedulerManager().processTickEnd();
            try {
                Thread.sleep(2);
            } catch (InterruptedException ignored) {
            }
        }
        assertTrue(done.get(), what);
    }

    private static boolean swordOf(Player p) {
        return p.getInventory().getItemStack(0).material() == Material.IRON_SWORD;
    }
}
