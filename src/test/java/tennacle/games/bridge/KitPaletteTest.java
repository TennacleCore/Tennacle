package tennacle.games.bridge;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.canvas.theme.Livery;
import io.github.term4.canvas.theme.Tint;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A kit's blocks are a role, resolved per seat at hand-out - so the network's palette reaches them without a
 * saved layout being touched: a layout is which SLOT a named item sits in, never what the item is.
 */
class KitPaletteTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    @Test
    void kitBlocksFollowThePalette() {
        List<Tint> before = Livery.ordinals();
        Livery.ordinals(List.of(Tint.parse("dark_purple"), Tint.parse("gold")));
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(bridge, null), SectionsTest.fixture());
        FakePlayer one = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "KitOne");
        FakePlayer two = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "KitTwo");
        try {
            one.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            two.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(one.player));
            assertTrue(game.join(two.player));
            await("both seated", () -> Game.of(one.player) == game && Game.of(two.player) == game);

            assertEquals(Material.PURPLE_TERRACOTTA, one.player.getInventory().getItemStack(3).material(),
                    "team 1 builds in the palette's first tint");
            assertEquals(Material.ORANGE_TERRACOTTA, two.player.getInventory().getItemStack(3).material(),
                    "team 2 in its second - gold pairs with the orange dye");
        } finally {
            game.end(Outcome.aborted());
            one.player.remove();
            two.player.remove();
            Livery.ordinals(before);
        }
    }
}
