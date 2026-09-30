package tennacle.games.bridge;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.event.PhaseChangeEvent;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.KitLayouts;
import tennacle.games.Match;
import tennacle.games.Matches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a seat leaves its kit when the first cage opens is its layout from then on; a later cage changes nothing. */
class KitCountdownTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        tennacle.games.TestWiring.ensure(instance);
    }

    @Test
    void firstCountdownSaves() {
        Game game = Matches.launch(Matches.mode("bridge"), Matches.platformFor("scrims"), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "CageRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "CageBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            assertTrue(Match.opening(game), "caged");

            var inv = red.player.getInventory();
            ItemStack sword = inv.getItemStack(0);
            ItemStack bow = inv.getItemStack(1);
            inv.setItemStack(0, bow);
            inv.setItemStack(1, ItemStack.AIR);
            inv.setItemStack(7, sword);
            BridgeRoundTest.tickUntil(game, "GO", () -> Match.live(game));
            assertEquals(7, KitLayouts.of(red.player, "bridge").get("sword"));
            assertEquals(0, KitLayouts.of(red.player, "bridge").get("bow"));
            assertNull(KitLayouts.of(blue.player, "bridge"), "left as handed: nothing to save");

            inv.setItemStack(8, inv.getItemStack(7));
            inv.setItemStack(7, ItemStack.AIR);
            EventDispatcher.call(new PhaseChangeEvent(game, Match.OPENING, Match.LIVE)); // a later cage's GO
            assertEquals(7, KitLayouts.of(red.player, "bridge").get("sword"), "only the first countdown counts");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }
}
