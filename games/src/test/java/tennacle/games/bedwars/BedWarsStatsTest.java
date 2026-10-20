package tennacle.games.bedwars;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.mechanics.damage.DamageSnapshot;
import io.github.term4.polyp.mechanics.damage.types.melee.MeleeDamage;
import io.github.term4.polyp.Polyp;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.TestWiring;
import tennacle.games.lives.Lives;
import tennacle.games.stats.Tally;
import tennacle.storage.MatchStore;
import tennacle.storage.StatStore;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedWarsStatsTest extends HeadlessServerTest {

    private static final Pos ROOM = new Pos(0.5, 100, 0.5);
    private static final Stats STATS = new Stats();
    private static final List<MatchStore.Line> LINES = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        Tally.install(STATS, new MatchStore() {
            @Override
            public @NotNull CompletableFuture<Void> add(@NotNull Match match) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public @NotNull CompletableFuture<Void> stats(@NotNull String match, @NotNull List<Line> lines, @NotNull List<Pair> pairs) {
                LINES.addAll(lines);
                return CompletableFuture.completedFuture(null);
            }
        });
    }

    @Test
    void aVoidFinalKillCounts() {
        Game game = BedWarsRoundTest.byArrival(Matches.launch(Matches.mode("bedwars-solo"), Matches.platformFor(null),
                BedWarsRoundTest.fixture()));
        game.state().set(Scope.GAME, Countdown.START_AT, 3);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwStatA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwStatB");
        FakePlayer c = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwStatC");
        try {
            for (FakePlayer p : List.of(a, b, c)) {
                p.player.setInstance(game.instance(), ROOM).join();
                assertTrue(Matches.enter(game, List.of(p.player)));
            }
            await("all seated", () -> Game.of(a.player) == game && Game.of(b.player) == game && Game.of(c.player) == game);
            BedWarsRoundTest.live(game);
            String aTeam = Match.teamOf(game, a.player), bTeam = Match.teamOf(game, b.player);
            BlockVec bBed = game.arena().marker(BedWars.BED, bTeam).boxes().getFirst().min();
            EventDispatcher.call(new PlayerBlockBreakEvent(a.player, game.instance(), Block.BLUE_BED, Block.AIR, bBed, BlockFace.TOP));
            hit(b, c, 2f);
            hit(b, a, 6f); // inside the hurt cooldown only a bigger hit lands
            game.module(Lives.class).fell(b.player);

            Tally tally = game.module(Tally.class);
            UUID ida = a.player.getUuid(), idb = b.player.getUuid(), idc = c.player.getUuid();
            assertEquals(1, tally.get(ida, "beds-broken"));
            assertEquals(1, tally.get(idb, "beds-lost"));
            assertEquals(1, tally.get(ida, "void-final-kills"), "the last hit takes the void");
            assertEquals(1, tally.get(ida, "final-kills"));
            assertEquals(1, tally.get(idb, "void-final-deaths"));
            assertEquals(1, tally.get(idb, "deaths"));
            assertEquals(1, tally.get(idc, "assists"), "the other hitter");
            assertEquals(0, tally.get(ida, "assists"), "the killer is no assist");

            game.end(Outcome.winner(aTeam));
            assertEquals(1, STATS.of(ida, "bedwars:final-kills"));
            assertEquals(1, STATS.of(ida, "bedwars-solo:final-kills"), "per mode too");
            assertEquals(1, STATS.of(ida, "bedwars:wins"));
            assertEquals(1, STATS.of(ida, "bedwars:winstreak"));
            assertEquals(1, STATS.of(ida, "bedwars-solo:best-winstreak"));
            assertEquals(1, STATS.of(idc, "bedwars:losses"));
            assertEquals(0, STATS.of(idc, "bedwars:winstreak"));
            assertEquals(1, STATS.of(idb, "bedwars:games"));
            assertTrue(LINES.contains(new MatchStore.Line(idc, "bedwars:assists", 1)), "the match keeps its rows");
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
            c.player.remove();
        }
    }

    @Test
    void anAbortCountsNothing() {
        Game game = BedWarsRoundTest.byArrival(Matches.launch(Matches.mode("bedwars-solo"), Matches.platformFor(null),
                BedWarsRoundTest.fixture()));
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwAbortA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwAbortB");
        try {
            for (FakePlayer p : List.of(a, b)) {
                p.player.setInstance(game.instance(), ROOM).join();
                assertTrue(Matches.enter(game, List.of(p.player)));
            }
            await("both seated", () -> Game.of(a.player) == game && Game.of(b.player) == game);
            BedWarsRoundTest.live(game);
            hit(b, a, 2f);
            game.module(Lives.class).fell(b.player);
            game.end(Outcome.aborted());
            assertEquals(0, STATS.of(a.player.getUuid(), "bedwars:kills"));
            assertEquals(0, STATS.of(a.player.getUuid(), "bedwars:games"));
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }

    private static void hit(FakePlayer victim, FakePlayer by, float amount) {
        Polyp.getInstance().services().damage().apply(DamageSnapshot.of(victim.player, MeleeDamage.INSTANCE)
                .withSource(by.player).withAmount(amount));
    }

    private static final class Stats implements StatStore {
        private final Map<String, Long> values = new ConcurrentHashMap<>();

        long of(UUID player, String stat) {
            return values.getOrDefault(player + "/" + stat, 0L);
        }

        @Override
        public @NotNull CompletableFuture<Long> get(@NotNull UUID player, @NotNull String stat) {
            return CompletableFuture.completedFuture(of(player, stat));
        }

        @Override
        public @NotNull CompletableFuture<Long> update(@NotNull UUID player, @NotNull String stat, @NotNull LongUnaryOperator fn) {
            return CompletableFuture.completedFuture(values.compute(player + "/" + stat, (k, old) -> fn.applyAsLong(old == null ? 0 : old)));
        }

        @Override
        public @NotNull CompletableFuture<List<Standing>> top(@NotNull String stat, int limit) {
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
