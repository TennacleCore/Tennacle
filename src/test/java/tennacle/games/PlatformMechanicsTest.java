package tennacle.games;

import tennacle.Mechanics;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.MechanicsKeys;
import io.github.term4.polyp.MechanicsProfile;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** {@code /duel <player> <mode> <platform>} picks the mechanics, not just the theme - on every mode. */
class PlatformMechanicsTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    private static MechanicsProfile profileOf(String mode, String platform, String preset, int seed) throws InterruptedException {
        FakePlayer a = FakePlayer.connect(instance, new Pos(seed + 0.5, 65, 0.5), "Plat" + seed + "A");
        FakePlayer b = FakePlayer.connect(instance, new Pos(seed + 2.5, 65, 2.5), "Plat" + seed + "B");
        Game game = null;
        try {
            Mode m = Matches.mode(mode);
            assertNotNull(m);
            game = Matches.start(m, Matches.platformFor(platform), List.of(List.of(a.player), List.of(b.player)));
            long deadline = System.currentTimeMillis() + 4000;
            while (Game.of(a.player) == null && System.currentTimeMillis() < deadline) {
                MinecraftServer.getSchedulerManager().processTick();
                Thread.sleep(10);
            }
            assertEquals(preset, game.rules().resolve(Scope.GAME, Presets.KEY),
                    mode + "/" + platform + " folds " + preset);
            MechanicsProfile world = Mechanics.polyp.profiles().world(game.shard());
            assertNotNull(world, mode + "/" + platform + " folded a world profile");
            return world;
        } finally {
            if (game != null) game.end(Outcome.draw());
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void platformPicksTheMechanics() throws InterruptedException {
        int seed = 400;
        for (String mode : List.of("sumo", "boxing", "bridge")) {
            for (var each : List.of(List.of("hypixel", "hypixel"), List.of("minemen", "mmc18"), List.of("scrims", "scrims18"))) {
                MechanicsProfile world = profileOf(mode, each.get(0), each.get(1), seed += 10);
                assertNotNull(world.get(MechanicsKeys.KNOCKBACK), mode + ": the preset's knockback rides the shard");
                assertNotNull(world.get(MechanicsKeys.COMPAT), mode + ": and its compat layer with it");
            }
        }
    }
}
