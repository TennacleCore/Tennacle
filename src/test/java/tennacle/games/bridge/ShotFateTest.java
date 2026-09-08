package tennacle.games.bridge;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.combat.Projectiles;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.MechanicsKeys;
import io.github.term4.polyp.mechanics.projectile.ProjectileSnapshot;
import io.github.term4.polyp.mechanics.projectile.ProjectileSystem;
import io.github.term4.polyp.mechanics.projectile.entities.ProjectileEntity;
import io.github.term4.polyp.mechanics.projectile.types.Arrow;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.Mechanics;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Minemen takes a shot down with the one who fired it; every other bridge leaves it flying. */
class ShotFateTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        tennacle.games.TestWiring.ensure(instance);
    }

    @Test
    void anArrowDiesWithItsShooter() {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor("minemen"), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "ShotRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "ShotBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            BridgeRoundTest.tickUntil(game, "GO", () -> Match.live(game));
            assertTrue(game.rules().resolve(Scope.GAME, Projectiles.DIE_WITH_SHOOTER), "minemen ships it on");

            game.state().set(Scope.GAME, Projectiles.DIE_WITH_SHOOTER, false);
            ProjectileEntity kept = shoot(red.player);
            dropIntoTheVoid(red.player);
            BridgeRoundTest.tickUntil(game, "the shooter is back", () -> red.player.getPosition().y() > 60);
            assertFalse(kept.isRemoved(), "off the rule a shot outlives its shooter");
            // the respawn teleport sends no move packet, and a plane stays crossed until one lands above it
            EventDispatcher.call(new PlayerMoveEvent(red.player, red.player.getPosition(), true));

            game.state().set(Scope.GAME, Projectiles.DIE_WITH_SHOOTER, true);
            ProjectileEntity taken = shoot(red.player);
            dropIntoTheVoid(red.player);
            BridgeRoundTest.tickUntil(game, "the shot goes with them", taken::isRemoved);
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    /** Only minemen: the house, hypixel and scrims all leave a shot in the air where it was. */
    @Test
    void everyOtherBridgeLeavesTheShotFlying() {
        Mode bridge = Matches.mode("bridge");
        for (String platform : new String[]{null, "hypixel", "scrims"}) {
            Game game = Matches.launch(bridge, Matches.platformFor(platform), BridgeRoundTest.fixture());
            try {
                assertFalse(game.rules().resolve(Scope.GAME, Projectiles.DIE_WITH_SHOOTER),
                        (platform == null ? "the house" : platform) + " keeps a shot in the air");
            } finally {
                game.end(Outcome.aborted());
            }
        }
    }

    private static ProjectileEntity shoot(Player shooter) {
        ProjectileEntity shot = new ProjectileSystem(Mechanics.polyp,
                Mechanics.polyp.profiles().resolve(shooter, MechanicsKeys.PROJECTILES))
                .launch(ProjectileSnapshot.of(shooter, Arrow.INSTANCE));
        assertNotNull(shot);
        Game game = Game.of(shooter);
        assertNotNull(game);
        await("the shot is in the game's world", () -> game.shard().entities().contains(shot));
        return shot;
    }

    private static void dropIntoTheVoid(Player faller) {
        EventDispatcher.call(new PlayerMoveEvent(faller, new Pos(0.5, 50, 0.5), true)); // under the fixture's void plane
    }
}
