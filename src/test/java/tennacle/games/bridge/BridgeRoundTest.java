package tennacle.games.bridge;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.archipelago.testsupport.MechanicsBoot;
import io.github.term4.barnacle.Arena;
import io.github.term4.barnacle.marker.LimitMarker;
import io.github.term4.barnacle.marker.PosMarker;
import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.bloom.Session;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.state.Scope;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import io.github.term4.polyp.mechanics.damage.DamageSnapshot;
import io.github.term4.polyp.mechanics.damage.types.melee.MeleeDamage;
import net.minestom.server.entity.attribute.Attribute;
import tennacle.Mechanics;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.Duels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The whole round on a synthetic map: grace, cage count, GO, a goal + re-cage, a void death + respawn. */
class BridgeRoundTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        tennacle.games.TestWiring.ensure(instance);
    }

    /** Deck along x, red west / blue east, goals at the far ends - every expected marker aboard. */
    private static Arena fixture() {
        Session s = Session.create("bridge-fixture", new ShardManager(), p -> {});
        try {
            for (int x = -8; x <= 8; x++) s.instance().setBlock(x, 64, 0, Block.WHITE_TERRACOTTA);
            s.addMarker(new RegionMarker("center", "", "", new BlockVec(0, 64, 0), new BlockVec(0, 64, 0)));
            s.addMarker(new RegionMarker("section", "red", "", new BlockVec(-8, 64, 0), new BlockVec(-1, 64, 0)));
            s.addMarker(new RegionMarker("section", "blue", "", new BlockVec(1, 64, 0), new BlockVec(8, 64, 0)));
            s.addMarker(new PosMarker("bridge:cage", "red", "", new Pos(-4.5, 65, 0.5, -90, 0)));
            s.addMarker(new PosMarker("bridge:cage", "blue", "", new Pos(5.5, 65, 0.5, 90, 0)));
            s.addMarker(new PosMarker("bridge:respawn", "red", "", new Pos(-4.5, 65, 0.5, -90, 0)));
            s.addMarker(new PosMarker("bridge:respawn", "blue", "", new Pos(5.5, 65, 0.5, 90, 0)));
            s.addMarker(new RegionMarker("bridge:goal", "red", "", new BlockVec(-8, 64, -1), new BlockVec(-7, 66, 1)));
            s.addMarker(new RegionMarker("bridge:goal", "blue", "", new BlockVec(7, 64, -1), new BlockVec(8, 66, 1)));
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
        Game game = Duels.grotto().launch(Bridge.type(), fixture());
        Duels.kindOf(game).onLaunch().accept(game); // what Duels.start runs: the ruleset fold + profile
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BridgeRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "BridgeBlue");
        try {
            // join from inside the arena: the synchronous branch surfaces module exceptions to the test
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player), "red seat claimed");
            assertTrue(game.join(blue.player), "blue seat claimed");
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            assertEquals("countdown", game.state().get(Scope.GAME, Duels.PHASE), "rounds start caged");
            // the ruleset fold: bridge's default look is scrims - trail on, roll off, mmc18 tuning intact
            var arrow = Mechanics.polyp.profiles().resolve(red.player,
                    io.github.term4.polyp.MechanicsKeys.PROJECTILES).typeConfig(
                    io.github.term4.polyp.mechanics.projectile.types.Arrow.KEY);
            assertEquals(Boolean.FALSE, arrow.critDamage.constantOrNull(), "scrims ruleset: no crit roll");
            assertEquals(Boolean.TRUE, arrow.critParticles.constantOrNull(), "scrims ruleset: trail stays");

            // a TEAM-scoped entry lands in the SAME world profile: red's arrows roll crits, blue's keep the look
            String redTeam = Bridge.teamOf(game, red.player);
            var reFold = Mechanics.polyp.profiles().world(game.shard()).toBuilder();
            tennacle.games.Ruleset.named("proof")
                    .path(io.github.term4.grotto.state.Scope.team(redTeam), "projectiles/minecraft:arrow/critDamage", true)
                    .apply(reFold, Mechanics.polyp.profiles().global(), game);
            Mechanics.polyp.profiles().setWorld(game.shard(), reFold.build());
            java.util.function.Function<Player, Boolean> crits = shooter -> {
                var snap = io.github.term4.polyp.mechanics.projectile.ProjectileSnapshot.of(shooter,
                        io.github.term4.polyp.mechanics.projectile.types.Arrow.INSTANCE);
                var ctx = io.github.term4.polyp.mechanics.projectile.ProjectileConfigResolver.ProjectileContext.of(snap,
                        Mechanics.polyp.services());
                return Mechanics.polyp.profiles().resolve(shooter, io.github.term4.polyp.MechanicsKeys.PROJECTILES)
                        .typeConfig(io.github.term4.polyp.mechanics.projectile.types.Arrow.KEY).critDamage.resolve(ctx);
            };
            assertEquals(Boolean.TRUE, crits.apply(red.player), "team-scoped: red rolls crits");
            assertEquals(Boolean.FALSE, crits.apply(blue.player), "blue keeps the scrims value");
            assertNull(Mechanics.polyp.profiles().player(red.player), "and nothing was pushed onto a player");

            // dropping a scope is a RE-FOLD over the base, never surgery on the built profile
            var handicap = tennacle.games.Ruleset.named("proof")
                    .path(io.github.term4.grotto.state.Scope.team(redTeam), "projectiles/minecraft:arrow/critDamage", true);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> handicap.apply(io.github.term4.polyp.MechanicsProfile.builder(), null),
                    "a scoped entry cannot fold without the game");
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> handicap.without(io.github.term4.grotto.state.Scope.team(redTeam))
                            .apply(io.github.term4.polyp.MechanicsProfile.builder(), null));
            Duels.kindOf(game).onLaunch().accept(game); // the launch fold again, without the handicap
            assertEquals(Boolean.FALSE, crits.apply(red.player), "re-folded from the base: red is back on the look");
            assertEquals(Boolean.TRUE, arrow.removeOnBlockHit.constantOrNull(), "bridge default rides along");
            await("kit applied", () -> swordOf(red.player) && swordOf(blue.player));
            // 1.8 leather chest+legs+boots = 3+2+1; if this drops to 0 nothing mitigates and TTK collapses
            assertEquals(6, (int) red.player.getAttributeValue(Attribute.ARMOR), "kit armor points");

            tickUntil(game, "GO", () -> "fighting".equals(game.state().get(Scope.GAME, Duels.PHASE)));
            assertTrue(game.state().get(Scope.GAME, Duels.STARTED_AT) >= 0, "the clock anchored at the first beat");

            Player scorer = "blue".equals(Bridge.teamOf(game, blue.player)) ? blue.player : red.player;
            // the scorer steps into the OTHER team's goal
            Pos goal = "blue".equals(Bridge.teamOf(game, scorer)) ? new Pos(-7.5, 65, 0.5) : new Pos(7.5, 65, 0.5);
            EventDispatcher.call(new PlayerMoveEvent(scorer, goal, true));
            String team = Bridge.teamOf(game, scorer);
            tickUntil(game, "goal counted", () -> game.state().get(Scope.team(team), Bridge.GOALS) == 1);
            tickUntil(game, "a goal re-cages", () -> "countdown".equals(game.state().get(Scope.GAME, Duels.PHASE)));

            tickUntil(game, "GO again", () -> "fighting".equals(game.state().get(Scope.GAME, Duels.PHASE)));
            Player faller = scorer == red.player ? blue.player : red.player;
            double home = faller.getPosition().x();
            EventDispatcher.call(new PlayerMoveEvent(faller, new Pos(0.5, 50, 0.5), true));
            tickUntil(game, "void respawn", () -> Math.abs(faller.getPosition().x() - home) < 0.01
                    && faller.getPosition().y() > 60);

            // respawn protection must lapse before a hit can land
            long shieldOver = game.state().tick() + 45;
            tickUntil(game, "protection lapses", () -> game.state().tick() >= shieldOver);
            // a lethal hit cancels its FatalDamageEvent and respawns - never the death screen
            int kills = game.state().get(Scope.slot(game.roster().slotOf(scorer).index()), Bridge.KILLS);
            Mechanics.polyp.services().damage().apply(DamageSnapshot.of(faller, MeleeDamage.INSTANCE)
                    .withSource(scorer).withAmount(100f));
            assertTrue(!faller.isDead(), "a kill never reaches the death screen");
            assertEquals(faller.getAttributeValue(Attribute.MAX_HEALTH), faller.getHealth(), "respawned refit");
            assertEquals(kills + 1, game.state().get(Scope.slot(game.roster().slotOf(scorer).index()), Bridge.KILLS),
                    "the killer is credited");

            game.decide(Outcome.draw(), 1);
        } finally {
            red.player.remove();
            blue.player.remove();
        }
    }

    /** The harness has no server tick loop - drive the game's instance (monotonic millis) ourselves. */
    private static void tickUntil(Game game, String what, java.util.function.Supplier<Boolean> done) {
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
