package tennacle.games.bridge;

import tennacle.boot.Mechanics;
import tennacle.games.Hits;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.mechanics.damage.DamageSnapshot;
import io.github.term4.polyp.mechanics.damage.types.melee.MeleeDamage;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.PlayerHandAnimationEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared counters: a swing, a landed hit and the damage it dealt land at the seats, and the cards read them. */
class HitsTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void swingsHitsAndDamage() {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "HitsRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "HitsBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            BridgeRoundTest.tickUntil(game, "GO", () -> Match.live(game));
            Respawns respawns = game.module(Respawns.class);
            respawns.unprotect(red.player);
            respawns.unprotect(blue.player);
            Slot mine = game.roster().slotOf(red.player);
            Slot theirs = game.roster().slotOf(blue.player);

            EventDispatcher.call(new PlayerHandAnimationEvent(red.player, PlayerHand.MAIN));
            EventDispatcher.call(new PlayerHandAnimationEvent(red.player, PlayerHand.MAIN));
            float before = blue.player.getHealth();
            Mechanics.polyp.services().damage().apply(DamageSnapshot.of(blue.player, MeleeDamage.INSTANCE)
                    .withSource(red.player).withAmount(3f));
            settle();

            assertEquals(2, Hits.at(game, mine, Hits.SWINGS));
            assertEquals(1, Hits.at(game, mine, Hits.LANDED), "the pipeline's verdict counts the hit");
            assertEquals(1, Hits.at(game, mine, Hits.COMBO));
            assertEquals(1, Hits.at(game, mine, Hits.BEST_COMBO));
            assertEquals(1, Hits.at(game, theirs, Hits.TAKEN));
            assertEquals(0, Hits.at(game, theirs, Hits.LANDED));
            float lost = before - blue.player.getHealth();
            assertTrue(lost > 0 && lost < 3f, "the kit's armor took some: " + lost);
            assertEquals(lost, game.state().get(Scope.slot(mine.index()), Hits.DEALT), 0.001f, "dealt is what the victim lost");
            assertEquals(String.format(java.util.Locale.ROOT, "%.1f", lost / 2), Hits.hearts(game, List.of(mine)), "in hearts");
            assertEquals("50%", Hits.share(Hits.at(game, mine, Hits.LANDED), Hits.at(game, mine, Hits.SWINGS)));
            assertEquals("N/A", Hits.share(0, 0), "before the first try");
            assertEquals(0, Hits.at(game, theirs, Hits.SWINGS), "the other seat never swung");
            assertEquals(1, Hits.sum(game, List.of(mine, theirs), Hits.LANDED), "a side sums its seats");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    private static void settle() {
        for (int i = 0; i < 2; i++) {
            MinecraftServer.getSchedulerManager().processTick();
            MinecraftServer.getSchedulerManager().processTickEnd();
        }
    }
}
