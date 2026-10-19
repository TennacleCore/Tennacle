package tennacle.games.bedwars;

import net.minestom.server.coordinate.Vec;
import io.github.term4.polyp.entity.DroppedItemEntity;
import tennacle.games.map.OffMap;
import tennacle.games.map.Structures;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;
import tennacle.games.items.BridgeEgg;
import tennacle.games.map.Building;

import io.github.term4.polyp.mechanics.projectile.ProjectileSnapshot;
import io.github.term4.polyp.api.event.projectile.ProjectileLaunchEvent;
import io.github.term4.polyp.mechanics.projectile.types.Fireball;
import io.github.term4.polyp.mechanics.projectile.types.Egg;
import net.minestom.server.network.packet.server.play.EntityEffectPacket;
import net.minestom.server.network.packet.server.play.EntityAttributesPacket;
import net.minestom.server.entity.attribute.Attribute;
import io.github.term4.polyp.platform.player.PolypPlayer;
import net.minestom.server.potion.PotionEffect;
import tennacle.boot.Mechanics;
import io.github.term4.polyp.mechanics.projectile.ProjectileSystem;
import io.github.term4.polyp.mechanics.projectile.entities.ProjectileEntity;
import io.github.term4.polyp.presets.vanilla18.Vanilla18;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A pop-up tower going up over ticks off a chest that never lands, and an egg's wool trailing five ticks behind. */
class StructuresTest extends HeadlessServerTest {

    private static final Pos ROOM = new Pos(0.5, 100, 0.5);

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path replays;

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        tennacle.glue.ReplayBridge.install();
        tennacle.games.watch.Replays.install(replays, new ShardManager());
    }

    @FunctionalInterface
    private interface Body {
        void run(Game game, FakePlayer a, FakePlayer b);
    }

    /** What a game throws or drops is gone once it is past the build circle by the margin; a mob would stay. */
    @Test
    void pastTheMarginIsGone() {
        round("OffA", (game, fa, fb) -> {
            game.state().set(Scope.GAME, OffMap.MARGIN, 0); // the fixture loads no chunk 50 past its circle of 30
            DroppedItemEntity inside = DroppedItemEntity.spawn(game.shard(), new Pos(29.5, 70, 0.5), Vec.ZERO,
                    ItemStack.of(Material.STONE), null, 10);
            DroppedItemEntity past = DroppedItemEntity.spawn(game.shard(), new Pos(31.5, 70, 0.5), Vec.ZERO,
                    ItemStack.of(Material.STONE), null, 10);
            ticks(game, 2);
            assertFalse(inside.isRemoved(), "inside the circle");
            assertTrue(past.isRemoved(), "past it");
            assertEquals(50, OffMap.MARGIN.initial(), "50 past the circle by default");
        });
    }

    /** One structure job that throws is dropped; the next still runs, and a dropped job lets go of what it holds. */
    @Test
    void aBadJobStopsOnlyItself() {
        round("JobA", (game, fa, fb) -> {
            Structures structures = game.module(Structures.class);
            int[] ran = new int[1];
            boolean[] released = new boolean[1];
            structures.add(new Structures.Job() {
                @Override public boolean tick() { throw new IllegalStateException("a broken job"); }
                @Override public void dropped() { released[0] = true; }
            });
            ticks(game, 1);
            structures.add(() -> ++ran[0] < 3);
            ticks(game, 4);
            assertTrue(released[0], "the broken job let go");
            assertEquals(3, ran[0], "and the next ran to its end");
        });
    }

    @Test
    void theTowerGoesUpTwoCellsATick() {
        round("TowerA", (game, fa, fb) -> {
            Player a = fa.player;
            a.teleport(new Pos(0.5, 66, -3.5, 0, 0)).join(); // facing south: the doorway opens north, toward the placer
            a.setItemInMainHand(ItemStack.of(Material.TRAPPED_CHEST, 2));
            BlockVec p = new BlockVec(0, 66, 0);
            var event = new PlayerBlockPlaceEvent(a, game.instance(), Block.TRAPPED_CHEST, BlockFace.TOP, p,
                    new Pos(0.5, 1, 0.5), PlayerHand.MAIN);
            EventDispatcher.call(event);
            assertTrue(event.isCancelled(), "no chest lands");
            assertEquals(1, a.getItemInMainHand().amount(), "one tower spent");
            ticks(game, 1);
            assertEquals(Block.AIR, game.shard().getBlock(new BlockVec(1, 66, -2)), "the walls wait for the floor");
            ticks(game, 56);
            Block wool = game.shard().getBlock(new BlockVec(1, 66, -2));
            assertTrue(wool.name().endsWith("_wool"), "a wall cell: " + wool.name());
            assertEquals(Block.AIR, game.shard().getBlock(new BlockVec(0, 66, -2)), "the doorway");
            assertEquals(Block.AIR, game.shard().getBlock(new BlockVec(0, 67, -2)), "two high");
            assertTrue(game.shard().getBlock(new BlockVec(0, 68, -2)).name().endsWith("_wool"), "closed above the door");
            Block ladder = game.shard().getBlock(p);
            assertEquals(Block.LADDER, ladder.defaultState(), "the column is a ladder");
            assertEquals("north", ladder.getProperty("facing"), "facing the door");
            assertTrue(game.shard().getBlock(new BlockVec(0, 65, 0)).name().endsWith("_wool"), "wool under the ladder");
            assertTrue(game.shard().getBlock(new BlockVec(-2, 70, 2)).name().endsWith("_wool"), "the platform's far right corner");
            assertTrue(game.shard().getBlock(new BlockVec(2, 72, -3)).name().endsWith("_wool"), "a crenel");
            assertTrue(Building.of(game).placed(new BlockVec(1, 66, -2)), "laid as the placer's own");
        });
    }

    @Test
    void anEggLeavesWoolFourTicksBehind() {
        round("EggA", (game, fa, fb) -> {
            Player a = fa.player;
            BridgeEgg eggs = game.module(BridgeEgg.class);
            Entity egg = new Entity(EntityType.EGG);
            egg.setInstance(game.instance(), new Pos(5.5, 67, 5.5)).join();
            try {
                assertTrue(eggs.allow(a));
                assertFalse(eggs.allow(a), "one a second");
                assertTrue(chat(fa).contains("Please wait 1.0s to use that again"), chat(fa).toString());
                eggs.trail(a, egg, egg.getPosition());
                ticks(game, 2);
                egg.teleport(new Pos(6.5, 67, 5.5)).join(); // the first step: bare, as captured
                ticks(game, 1);
                egg.teleport(new Pos(7.5, 67, 5.5)).join(); // the second: the first wool
                ticks(game, 4);
                assertEquals(Block.AIR, game.shard().getBlock(new BlockVec(7, 65, 5)), "nothing yet");
                ticks(game, 1);
                assertTrue(game.shard().getBlock(new BlockVec(7, 65, 5)).name().endsWith("_wool"), "under the egg");
                assertTrue(game.shard().getBlock(new BlockVec(8, 65, 5)).name().endsWith("_wool"), "the nearer x neighbor");
                assertTrue(game.shard().getBlock(new BlockVec(7, 65, 6)).name().endsWith("_wool"), "the nearer z neighbor");
                assertEquals(Block.AIR, game.shard().getBlock(new BlockVec(6, 65, 5)), "not the first step");
                assertEquals(Block.AIR, game.shard().getBlock(new BlockVec(5, 65, 5)), "nothing under the throw itself");
                egg.remove();
                ticks(game, 6);
                assertTrue(Building.of(game).placed(new BlockVec(7, 65, 5)), "a member's block");
            } finally {
                egg.remove();
            }
        });
    }

    @Test
    void aThrowSlowsTheThrower() {
        round("FireA", (game, fa, fb) -> {
            Player a = fa.player;
            // the harness boots mechanics without the projectile system Mechanics.install() adds
            ProjectileSystem projectiles = new ProjectileSystem(Mechanics.polyp, Vanilla18.projectiles());
            // a throw is a use-item packet's: its echoes to the thrower pass the self filter
            ((PolypPlayer) a).suppressSelf(() -> assertNotNull(projectiles.launch(
                    ProjectileSnapshot.of(a, Fireball.INSTANCE).withConfig(Vanilla18.projectiles())), "the fireball flies"));
            assertTrue(a.hasEffect(PotionEffect.SLOWNESS), "the throw slows the thrower");
            EntityEffectPacket sent = fa.sent(EntityEffectPacket.class).stream()
                    .filter(p -> p.potion().effect() == PotionEffect.SLOWNESS).toList().getLast();
            assertEquals(1, sent.potion().amplifier(), "Slowness II, the effect the client is sent");
            assertEquals(20, sent.potion().duration(), "for 20 ticks");
            EntityAttributesPacket speed = fa.sent(EntityAttributesPacket.class).stream()
                    .filter(p -> p.entityId() == a.getEntityId() && p.properties().stream()
                            .anyMatch(property -> property.attribute() == Attribute.MOVEMENT_SPEED)).toList().getLast();
            assertTrue(speed.properties().stream().flatMap(property -> property.modifiers().stream())
                    .anyMatch(m -> Math.abs(m.amount() + 0.3) < 1e-9), "the slowdown reaches the client as its own speed");
        });
    }

    @Test
    void theFirstWoolIsTwoTicksIntoTheFlight() {
        round("EggB", (game, fa, fb) -> {
            Player a = fa.player;
            ProjectileSystem projectiles = new ProjectileSystem(Mechanics.polyp, Vanilla18.projectiles());
            Pos from = new Pos(5.5, 70, 5.5);
            Entity egg = projectiles.launch(ProjectileSnapshot.of(a, Egg.INSTANCE).withItem(ItemStack.of(Material.EGG))
                    .withSpawnPos(from).withConfig(Vanilla18.projectiles()));
            assertNotNull(egg, "the egg flies");
            try {
                ticks(game, 8); // the harness never moves it: it sits on the throw
                assertEquals(Block.AIR, game.shard().getBlock(new BlockVec(5, 68, 5)), "nothing under the throw");
                egg.teleport(new Pos(7.5, 70, 5.5)).join();
                ticks(game, 1);
                egg.teleport(new Pos(9.5, 70, 5.5)).join();
                ticks(game, 6);
                assertEquals(Block.AIR, game.shard().getBlock(new BlockVec(7, 68, 5)), "the first step stays bare");
                assertTrue(game.shard().getBlock(new BlockVec(9, 68, 5)).name().endsWith("_wool"), "the first wool is two ticks in");
            } finally {
                egg.remove();
            }
        });
    }

    /** Captured 2026-10-19: Hypixel's egg goes exactly where the thrower looks, 9 of 9 straight down. */
    @Test
    void aBridgeEggFliesStraight() {
        round("EggD", (game, fa, fb) -> {
            Player a = fa.player;
            a.teleport(new Pos(0.5, 70, 0.5, 0, 90)).join();
            ProjectileSystem projectiles = new ProjectileSystem(Mechanics.polyp, Vanilla18.projectiles());
            ProjectileEntity egg = projectiles.launch(ProjectileSnapshot.of(a, Egg.INSTANCE).withItem(ItemStack.of(Material.EGG)).withConfig(Vanilla18.projectiles()));
            assertNotNull(egg, "the egg flies");
            try {
                assertEquals(0, egg.getVelocity().x(), 1e-9, "no spread across");
                assertEquals(0, egg.getVelocity().z(), 1e-9);
                assertTrue(egg.getVelocity().y() < 0, "straight down");
            } finally {
                egg.remove();
            }
        });
    }

    /** The egg moved by hand the way the server moves it: the first wool lands two steps out (captured, both servers). */
    @Test
    void theFirstWoolSitsTwoStepsOut() {
        round("EggC", (game, fa, fb) -> {
            Player a = fa.player;
            a.teleport(new Pos(0.5, 70, 0.5, 0, 0)).join();
            ProjectileSystem projectiles = new ProjectileSystem(Mechanics.polyp, Vanilla18.projectiles());
            Pos[] thrown = new Pos[1];
            game.on(ProjectileLaunchEvent.class, ProjectileLaunchEvent::shooter, e -> thrown[0] = e.spawnPos());
            ProjectileEntity egg = projectiles.launch(ProjectileSnapshot.of(a, Egg.INSTANCE).withItem(ItemStack.of(Material.EGG)).withConfig(Vanilla18.projectiles()));
            assertNotNull(egg, "the egg flies");
            assertNotNull(thrown[0]);
            projectiles.firstStep(egg);
            BlockVec first = null;
            int at = 0;
            try {
                for (int tick = 1; tick <= 12 && first == null; tick++) {
                    ticks(game, 1);
                    egg.tick(System.nanoTime());
                    for (int x = -6; x <= 6 && first == null; x++) {
                        for (int z = -6; z <= 6 && first == null; z++) {
                            for (int y = 60; y <= 72 && first == null; y++) {
                                if (game.shard().getBlock(x, y, z).name().endsWith("_wool")) {
                                    first = new BlockVec(x, y, z);
                                    at = tick;
                                }
                            }
                        }
                    }
                }
            } finally {
                egg.remove();
            }
            assertNotNull(first, "wool came");
            double out = Math.hypot(first.x() + 0.5 - thrown[0].x(), first.z() + 0.5 - thrown[0].z());
            assertTrue(out >= 2.0, "the first wool is two steps out: " + first + " at tick " + at + ", " + out + " from " + thrown[0]);
            assertTrue(at <= 8, "and comes within eight ticks, not " + at);
        });
    }

    @Test
    void aSecondFireballWaits() {
        round("FireB", (game, fa, fb) -> {
            Player a = fa.player;
            ProjectileSystem projectiles = new ProjectileSystem(Mechanics.polyp, Vanilla18.projectiles());
            assertNotNull(projectiles.launch(ProjectileSnapshot.of(a, Fireball.INSTANCE).withConfig(Vanilla18.projectiles())));
            ticks(game, 5);
            assertNull(projectiles.launch(ProjectileSnapshot.of(a, Fireball.INSTANCE).withConfig(Vanilla18.projectiles())), "refused inside the half second");
            assertTrue(chat(fa).contains("Please wait 0.5s to use that again"), chat(fa).toString());
            ticks(game, 5);
            assertNotNull(projectiles.launch(ProjectileSnapshot.of(a, Fireball.INSTANCE).withConfig(Vanilla18.projectiles())), "the cooldown is over");
        });
    }

    private static void round(String name, Body body) {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = BedWarsRoundTest.byArrival(Matches.launch(solo, Matches.platformFor(null), BedWarsRoundTest.fixture()));
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), name);
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), name + "F");
        try {
            a.player.setInstance(game.instance(), ROOM).join();
            b.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(a.player)));
            assertTrue(Matches.enter(game, List.of(b.player)));
            await("both seated", () -> Game.of(a.player) == game && Game.of(b.player) == game);
            BedWarsRoundTest.live(game);
            assertEquals("1", Match.teamOf(game, a.player), "the fixture's first side");
            a.sent.clear();
            b.sent.clear();
            body.run(game, a, b);
        } finally {
            tennacle.games.watch.Replays.discard(game); // no replay still writing into the temp dir as it goes
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }

    private static void ticks(Game game, int n) {
        long until = game.tick() + n;
        BedWarsRoundTest.tickUntil(game, n + " ticks", () -> game.tick() >= until);
    }

    private static List<String> chat(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }
}
