package tennacle.games.bedwars;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.MechanicsKeys;
import io.github.term4.polyp.mechanics.damage.DamageSnapshot;
import io.github.term4.polyp.mechanics.damage.DamageSystem;
import io.github.term4.polyp.mechanics.damage.types.projectile.ProjectileDamage;
import io.github.term4.polyp.mechanics.projectile.types.Arrow;
import io.github.term4.polyp.mechanics.projectile.types.ProjectileTypeConfig;
import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.boot.Mechanics;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** No bow boost, and a teammate's arrow does nothing (the user, 2026-09-28). */
class BowsTest extends HeadlessServerTest {

    private static final Pos ROOM = new Pos(0.5, 100, 0.5);

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void anOwnArrowPassesThrough() {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = BedWarsRoundTest.byArrival(Matches.launch(solo, Matches.platformFor(null), BedWarsRoundTest.fixture()));
        try {
            var arrow = Mechanics.polyp.profiles().world(game.shard()).get(MechanicsKeys.PROJECTILES).typeConfig(Arrow.KEY);
            assertEquals(ProjectileTypeConfig.HitResponse.PASS_THROUGH, arrow.selfHit.constantOrNull());
        } finally {
            game.end(Outcome.aborted());
        }
    }

    @Test
    void aTeammatesArrowIsInert() {
        Mode doubles = Matches.mode("bedwars-doubles");
        Game game = BedWarsRoundTest.byArrival(Matches.launch(doubles, Matches.platformFor(null), BedWarsRoundTest.fixture()));
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 2);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BowMateA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BowMateB");
        FakePlayer c = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BowFoe");
        try {
            a.player.setInstance(game.instance(), ROOM).join();
            b.player.setInstance(game.instance(), ROOM).join();
            c.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player, b.player)));
            assertTrue(Matches.enter(game, List.of(c.player)));
            await("all seated", () -> Game.of(a.player) == game && Game.of(b.player) == game && Game.of(c.player) == game);
            assertEquals(Match.teamOf(game, a.player), Match.teamOf(game, b.player));
            assertNotEquals(Match.teamOf(game, a.player), Match.teamOf(game, c.player));
            BedWarsRoundTest.live(game);
            DamageSystem damage = Mechanics.polyp.services().damage();
            float health = b.player.getHealth();
            // the damage call an arrow's hit makes
            assertEquals(DamageSystem.DamageOutcome.IMMUNE, damage.apply(DamageSnapshot.of(b.player, ProjectileDamage.INSTANCE)
                    .withSource(a.player).withAmount(4f)), "a teammate's arrow is refused, push and all");
            assertEquals(health, b.player.getHealth());
            assertTrue(damage.apply(DamageSnapshot.of(c.player, ProjectileDamage.INSTANCE)
                    .withSource(a.player).withAmount(4f)).landed(), "an enemy's lands");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
            c.player.remove();
        }
    }
}
