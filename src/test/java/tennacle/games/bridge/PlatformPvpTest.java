package tennacle.games.bridge;

import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.MechanicsKeys;
import io.github.term4.polyp.mechanics.projectile.types.Arrow;
import io.github.term4.polyp.mechanics.projectile.types.ProjectileTypeConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.Mechanics;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.Presets;
import tennacle.games.TestWiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** A platform picks its own pvp, and only minemen lets an arrow come back to its shooter. */
class PlatformPvpTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    private static void onPlatform(String platform, String preset, ProjectileTypeConfig.HitResponse selfHit) {
        Mode bridge = Matches.mode("bridge");
        assertNotNull(bridge);
        Game game = Matches.launch(bridge, Matches.platformFor(bridge, platform), bridge.arena().get());
        try {
            assertEquals(preset, game.rules().resolve(Scope.GAME, Presets.KEY), platform + " pvp");
            var arrow = Mechanics.polyp.profiles().world(game.shard())
                    .get(MechanicsKeys.PROJECTILES).typeConfig(Arrow.KEY);
            assertEquals(selfHit, arrow.selfHit.constantOrNull(), platform + " self hit");
        } finally {
            game.end(Outcome.aborted());
        }
    }

    @Test
    void minemenBoosts() {
        onPlatform("minemen", "mmc18", ProjectileTypeConfig.HitResponse.HIT);
    }

    @Test
    void hypixelDoesNot() {
        onPlatform("hypixel", "hypixel", ProjectileTypeConfig.HitResponse.PASS_THROUGH);
    }

    @Test
    void scrimsDoesNot() {
        onPlatform("scrims", "scrims18", ProjectileTypeConfig.HitResponse.PASS_THROUGH);
    }
}
