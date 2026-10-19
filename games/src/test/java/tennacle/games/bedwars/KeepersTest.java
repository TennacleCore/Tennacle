package tennacle.games.bedwars;

import tennacle.games.Keepers;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.barnacle.marker.PointMarker;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.api.event.item.ItemSpawnEvent;
import io.github.term4.polyp.entity.DroppedItemEntity;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeepersTest extends HeadlessServerTest {

    private static final Pos ROOM = new Pos(0.5, 100, 0.5);

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path replays;

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        tennacle.glue.ReplayBridge.install();
        tennacle.games.watch.Replays.install(replays, new ShardManager());
    }

    /** The user, 2026-10-18: nobody can walk in for it, so after about 3 s it is back with whoever dropped it. */
    @Test
    void aDropByAKeeperComesBack() {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = BedWarsRoundTest.byArrival(Matches.launch(solo, Matches.platformFor(null), BedWarsRoundTest.fixture()));
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "KeepA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "KeepB");
        try {
            a.player.setInstance(game.instance(), ROOM).join();
            b.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player)));
            assertTrue(Matches.enter(game, List.of(b.player)));
            BedWarsRoundTest.live(game);
            PointMarker shop = game.arena().marker(BedWars.SHOP, Match.teamOf(game, a.player));
            assertNotNull(shop, "the fixture's shopkeeper");
            int before = count(a, Material.IRON_INGOT);
            DroppedItemEntity item = DroppedItemEntity.spawn(game.shard(), new Pos(shop.point()), Vec.ZERO,
                    ItemStack.of(Material.IRON_INGOT, 5), null, 40, ItemSpawnEvent.Cause.PLAYER_DROP, a.player);
            assertNotNull(item);
            int wait = game.rules().resolve(Scope.GAME, Keepers.RETURN);
            ticks(game, wait - 5);
            assertFalse(item.isRemoved(), "still lying there");
            ticks(game, 10);
            assertTrue(item.isRemoved(), "picked up for them");
            assertEquals(before + 5, count(a, Material.IRON_INGOT), "back in the dropper's inventory");
        } finally {
            tennacle.games.watch.Replays.discard(game);
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }

    private static int count(FakePlayer p, Material material) {
        int n = 0;
        for (ItemStack stack : p.player.getInventory().getItemStacks()) if (stack.material() == material) n += stack.amount();
        return n;
    }

    private static void ticks(Game game, int n) {
        long until = game.tick() + n;
        BedWarsRoundTest.tickUntil(game, n + " ticks", () -> game.tick() >= until);
    }
}
