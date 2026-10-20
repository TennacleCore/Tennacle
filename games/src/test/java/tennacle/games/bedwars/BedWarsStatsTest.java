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
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import org.jetbrains.annotations.NotNull;
import io.github.term4.polyp.api.event.container.ContainerChangeEvent;
import io.github.term4.polyp.api.event.container.ContainerOpenEvent;
import io.github.term4.polyp.api.event.item.ItemSpawnEvent;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import io.github.term4.polyp.entity.DroppedItemEntity;
import io.github.term4.polyp.world.MechanicsWorld;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.item.PickupItemEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.Nullable;
import tennacle.games.shop.Offer;
import tennacle.games.shop.Price;
import tennacle.games.shop.Refusal;
import tennacle.games.shop.Sale;
import tennacle.games.shop.Shop;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.boot.Mechanics;
import tennacle.games.Match;
import io.github.term4.polyp.mechanics.projectile.ProjectileSnapshot;
import io.github.term4.polyp.mechanics.projectile.ProjectileSystem;
import io.github.term4.polyp.mechanics.projectile.types.Fireball;
import io.github.term4.polyp.mechanics.projectile.types.Pearl;
import io.github.term4.polyp.presets.vanilla18.Vanilla18;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                BedWarsRoundTest.fixture(false)));
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
            BlockVec against = bBed.sub(1, 0, 0);
            a.player.teleport(new Pos(6.5, 65, 0.5)).join();
            a.player.setItemInMainHand(ItemStack.of(Material.WHITE_WOOL, 8));
            var wool = new PlayerBlockPlaceEvent(a.player, game.instance(), Block.WHITE_WOOL, BlockFace.TOP, against,
                    new Pos(0.5, 1, 0.5), PlayerHand.MAIN);
            EventDispatcher.call(wool);
            assertFalse(wool.isCancelled(), "placed against the bed");
            EventDispatcher.call(new PlayerBlockBreakEvent(a.player, game.instance(), Block.WHITE_WOOL, Block.AIR, against, BlockFace.TOP));
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
            assertEquals(1, tally.get(ida, "blocks-placed"), "blocks-placed");
            assertEquals(1, tally.get(ida, "blocks-broken"), "blocks-broken");
            assertEquals(1, tally.get(ida, "bed-defense-broken"), "bed-defense-broken");
            assertEquals(1, tally.get(idc, "hits"), "hits");
            assertEquals(2, tally.get(idb, "hits-taken"));
            assertTrue(tally.get(idc, "damage-dealt") > 0, "in tenths");
            assertEquals(tally.get(idc, "damage-dealt"), tally.pairOf(idc, idb.toString(), "damage-dealt"), "and against whom");

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
    void resourcesKnowTheirSource() {
        Game game = BedWarsRoundTest.byArrival(Matches.launch(Matches.mode("bedwars-solo"), Matches.platformFor(null),
                BedWarsRoundTest.fixture()));
        game.state().set(Scope.GAME, Countdown.START_AT, 3);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwResA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwResB");
        FakePlayer c = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwResC");
        try {
            for (FakePlayer p : List.of(a, b, c)) {
                p.player.setInstance(game.instance(), ROOM).join();
                assertTrue(Matches.enter(game, List.of(p.player)));
            }
            await("all seated", () -> Game.of(a.player) == game && Game.of(b.player) == game && Game.of(c.player) == game);
            BedWarsRoundTest.live(game);
            Tally tally = game.module(Tally.class);
            UUID ida = a.player.getUuid(), idb = b.player.getUuid(), idc = c.player.getUuid();

            Point diamond = game.arena().markers(BedWars.DIAMOND).getFirst().point();
            BedWarsRoundTest.tickUntil(game, "the first diamond", () -> lyingAt(game, diamond) != null);
            EventDispatcher.call(new PickupItemEvent(a.player, lyingAt(game, diamond)));
            assertEquals(1, tally.get(ida, "diamond-from-generators"));

            DroppedItemEntity thrown = DroppedItemEntity.spawn(MechanicsWorld.of(b.player), b.player.getPosition(), Vec.ZERO,
                    ItemStack.of(Material.GOLD_INGOT, 2), null, 0, ItemSpawnEvent.Cause.PLAYER_DROP, b.player);
            EventDispatcher.call(new PickupItemEvent(a.player, thrown));
            assertEquals(2, tally.get(ida, "gold-from-players"));

            b.player.getInventory().addItemStack(ItemStack.of(Material.IRON_INGOT, 5));
            hit(b, a, 2f);
            game.module(Lives.class).fell(b.player);
            assertEquals(5, tally.get(ida, "iron-stolen"));
            assertEquals(5, tally.get(idb, "iron-lost-to-players"));
            assertEquals(0, tally.get(idb, "iron-lost-to-void"), "handed over, not lost");

            c.player.getInventory().addItemStack(ItemStack.of(Material.EMERALD, 3));
            game.module(Lives.class).fell(c.player);
            assertEquals(3, tally.get(idc, "emerald-lost-to-void"), "nobody to hand them to");

            MechanicsWorld world = MechanicsWorld.of(a.player);
            EventDispatcher.call(new ContainerOpenEvent(world, new BlockVec(0, 65, 0), "test-chest", a.player));
            EventDispatcher.call(new ContainerChangeEvent(world, "test-chest", 0, ItemStack.of(Material.IRON_INGOT, 5), ItemStack.AIR));
            EventDispatcher.call(new ContainerChangeEvent(world, "test-chest", 1, ItemStack.AIR, ItemStack.of(Material.IRON_INGOT, 2)));
            EventDispatcher.call(new InventoryCloseEvent(a.player.getInventory(), a.player, true));
            assertEquals(3, tally.get(ida, "iron-from-chests"), "net of what went back");

            a.player.getInventory().addItemStack(ItemStack.of(Material.IRON_INGOT, 10));
            Shop shop = game.module(Shop.class);
            assertTrue(shop.buy(a.player, new Offer() {
                @Override
                public @NotNull String id() {
                    return "test-wool";
                }

                @Override
                public @NotNull Price price(@NotNull Game g, @NotNull Player buyer) {
                    return Price.of(4, Resources.IRON);
                }

                @Override
                public @Nullable Refusal refusal(@NotNull Game g, @NotNull Player buyer) {
                    return null;
                }

                @Override
                public boolean grant(@NotNull Game g, @NotNull Player buyer) {
                    return true;
                }
            }) instanceof Sale.Sold);
            assertEquals(4, tally.get(ida, "iron-spent"));
            assertEquals(1, tally.get(ida, "items-bought"));

            ProjectileSystem projectiles = new ProjectileSystem(Mechanics.polyp, Vanilla18.projectiles());
            Entity pearl = projectiles.launch(ProjectileSnapshot.of(a.player, Pearl.INSTANCE).withConfig(Vanilla18.projectiles()));
            Entity fireball = projectiles.launch(ProjectileSnapshot.of(a.player, Fireball.INSTANCE).withConfig(Vanilla18.projectiles()));
            if (pearl != null) pearl.remove();
            if (fireball != null) fireball.remove();
            assertEquals(1, tally.get(ida, "pearls-thrown"));
            assertEquals(1, tally.get(ida, "fireballs-thrown"));
        } finally {
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
            c.player.remove();
        }
    }

    private static @Nullable DroppedItemEntity lyingAt(Game game, Point at) {
        for (Entity entity : game.instance().getNearbyEntities(at, 2)) {
            if (entity instanceof DroppedItemEntity item && item.getItemStack().material() == Material.DIAMOND) return item;
        }
        return null;
    }

    @Test
    void placesCountBySecond() {
        Game game = BedWarsRoundTest.byArrival(Matches.launch(Matches.mode("bedwars-solo"), Matches.platformFor(null),
                BedWarsRoundTest.fixture()));
        game.state().set(Scope.GAME, Countdown.START_AT, 3);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwPlaceA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwPlaceB");
        FakePlayer c = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "BwPlaceC");
        try {
            for (FakePlayer p : List.of(a, b, c)) {
                p.player.setInstance(game.instance(), ROOM).join();
                assertTrue(Matches.enter(game, List.of(p.player)));
            }
            await("all seated", () -> Game.of(a.player) == game && Game.of(b.player) == game && Game.of(c.player) == game);
            BedWarsRoundTest.live(game);
            Tally tally = game.module(Tally.class);
            String aTeam = Match.teamOf(game, a.player);
            a.player.teleport(new Pos(-11.5, 65, 1.5)).join(); // their own base box
            b.player.teleport(new Pos(-10.5, 65, -1.5)).join(); // a's, its bed standing
            c.player.teleport(new Pos(0.5, 65, -12.5)).join(); // by the diamonds
            BedWarsRoundTest.tickUntil(game, "a sampled second", () -> tally.get(c.player.getUuid(), "time-at-diamonds") >= 1);
            assertTrue(tally.get(a.player.getUuid(), "time-at-base") >= 1);
            assertTrue(tally.get(b.player.getUuid(), "time-at-bases-alive") >= 1);
            assertTrue(tally.get(b.player.getUuid(), "time-at-other-bases") >= 1);
            assertTrue(tally.pairOf(b.player.getUuid(), aTeam, "time-at-base") >= 1, "kept against the side");
            assertEquals(0, tally.get(c.player.getUuid(), "time-at-mid"));

            c.player.teleport(new Pos(1.5, 65, 1.5)).join();
            BedWarsRoundTest.tickUntil(game, "a second at the middle", () -> tally.get(c.player.getUuid(), "time-at-mid") >= 1);
            assertTrue(tally.get(c.player.getUuid(), "time-played") >= 2);
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
