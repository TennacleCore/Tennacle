package tennacle.games;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameType;
import io.github.term4.grotto.Markers;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.modules.flow.Phases;
import io.github.term4.grotto.roster.SlotLayout;
import io.github.term4.polyp.entity.DroppedItemEntity;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.item.PickupItemEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drops on a clock under a lying cap, a restart, and a pickup shared with the teammates beside it. */
class GeneratorsTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    private static GameType type(Generators generators) {
        return GameType.builder("generators-test").slots(SlotLayout.groups(Markers.SPAWN, 2))
                .rule(Countdown.FROM, 1).rule(Countdown.GRACE, 1L).rule(Countdown.INTERVAL, 1L)
                .modules(() -> Phases.of(Match.OPENING, Match.LIVE, Match.OVER), Countdown::of, () -> generators)
                .build();
    }

    private static List<DroppedItemEntity> lying(Game game, Point at, Material material) {
        return game.instance().getEntities().stream()
                .filter(e -> e instanceof DroppedItemEntity item && item.getItemStack().material() == material
                        && e.getPosition().distance(at) <= 3)
                .map(e -> (DroppedItemEntity) e).toList();
    }

    @Test
    void dropsOnScheduleUnderACap() {
        Game game = Matches.grotto().launch(type(Generators.of()), Matches.mode("bedfight").arena().get());
        FakePlayer p = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "GenA");
        try {
            p.player.setInstance(game.instance(), game.arena().meta().spawn()).join();
            assertTrue(game.join(p.player));
            Point at = game.roster().slotOf(p.player).spawn().add(0, 0.5, 0);
            Generators generators = game.module(Generators.class);
            Generators.Pool pool = generators.pool(2);
            Generators.Generator g = generators.add(at, ItemStack.of(Material.IRON_INGOT), 5, pool);
            g.start(1);
            TimelineTest.tickUntil(game, "two drops", () -> pool.lying() == 2);
            for (int i = 0; i < 20; i++) TimelineTest.tickUntil(game, "a tick", () -> true);
            assertEquals(2, pool.lying(), "the cap holds while both lie there");
            assertEquals(2, lying(game, at, Material.IRON_INGOT).size());

            lying(game, at, Material.IRON_INGOT).getFirst().remove(); // carried away
            TimelineTest.tickUntil(game, "the next drop fills the gap", () -> pool.lying() == 2);

            g.stop();
            for (DroppedItemEntity item : lying(game, at, Material.IRON_INGOT)) item.remove();
            g.period(200);
            g.restart();
            assertEquals(1, lying(game, at, Material.IRON_INGOT).size(), "a restart drops on the spot");
            assertTrue(g.due() > 150, "and the clock runs from here: " + g.due());
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            p.player.remove();
        }
    }

    @Test
    void aPickupCopiesToTeammatesBeside() {
        Game game = Matches.grotto().launch(type(Generators.of().share(2.5, s -> true)), Matches.mode("bedfight").arena().get());
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "ShareA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "ShareB");
        FakePlayer c = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "ShareC");
        try {
            for (FakePlayer f : List.of(a, b, c)) f.player.setInstance(game.instance(), game.arena().meta().spawn()).join();
            String team = game.roster().teams().getFirst().id();
            assertTrue(game.join(a.player, team));
            assertTrue(game.join(b.player, team));
            assertTrue(game.join(c.player));
            assertNotEquals(team, Match.teamOf(game, c.player), "the third is a foe");
            Point at = game.roster().slotOf(a.player).spawn().add(0, 0.5, 0);
            Generators generators = game.module(Generators.class);
            Generators.Generator g = generators.add(at, ItemStack.of(Material.GOLD_INGOT), 100, generators.pool(4));
            g.restart();
            DroppedItemEntity item = lying(game, at, Material.GOLD_INGOT).getFirst();
            Pos beside = new Pos(item.getPosition().x() + 1, item.getPosition().y(), item.getPosition().z());
            b.player.teleport(beside).join();
            c.player.teleport(beside).join();
            a.player.teleport(item.getPosition()).join();

            EventDispatcher.call(new PickupItemEvent(a.player, item));
            assertEquals(1, count(a.player, Material.GOLD_INGOT), "the picker has it");
            assertEquals(1, count(b.player, Material.GOLD_INGOT), "the teammate beside gets a copy");
            assertEquals(0, count(c.player, Material.GOLD_INGOT), "the foe beside gets nothing");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
            c.player.remove();
        }
    }

    private static int count(net.minestom.server.entity.Player player, Material material) {
        int n = 0;
        for (ItemStack stack : player.getInventory().getItemStacks()) if (stack.material() == material) n += stack.amount();
        return n;
    }
}
