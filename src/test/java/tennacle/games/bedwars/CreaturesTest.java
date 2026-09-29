package tennacle.games.bedwars;

import tennacle.boot.Mechanics;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;
import tennacle.games.items.Creatures;
import tennacle.games.beds.Spoils;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.config.KeySet;
import io.github.term4.polyp.mechanics.damage.types.mob.MobDamage;
import io.github.term4.polyp.mechanics.mobs.kinds.EnderDragonEntity;
import io.github.term4.polyp.mechanics.mobs.kinds.IronGolemEntity;
import io.github.term4.polyp.mechanics.mobs.kinds.SilverfishEntity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import org.junit.jupiter.api.BeforeAll;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A bedbug and a defender wearing their side, a creature's kill credited to its owner without the loot, and Sudden Death's dragons. */
class CreaturesTest extends HeadlessServerTest {

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

    @Test
    void aBedbugIsItsOwners() {
        round("BugA", (game, fa, fb) -> {
            Creatures creatures = game.module(Creatures.class);
            SilverfishEntity fish = creatures.bedbug(fa.player, new Pos(0.5, 65, 0.5));
            assertNotNull(fish);
            String name = PlainTextComponentSerializer.plainText().serialize(fish.getCustomName());
            assertTrue(name.endsWith(" Silverfish"), name);
            assertTrue(name.startsWith(Match.teamName(game, fa.player)), name);
            assertEquals(8.0f, fish.getHealth(), 0.01f);
            assertTrue(fish.hasEffect(PotionEffect.STRENGTH) && fish.hasEffect(PotionEffect.RESISTANCE), "its swirl's two effects");
            assertTrue(fish.sides().same(fish, fa.player), "its owner's side");
            assertFalse(fish.sides().same(fish, fb.player), "never the other side's");
            assertTrue(chat(fa).contains("Your creature will fight for you for 15 seconds."), chat(fa).toString());
            Entity tag = game.instance().getEntities().stream()
                    .filter(e -> e.getEntityType() == EntityType.ARMOR_STAND
                            && e.getPosition().distance(fish.getPosition().add(0, -1.38 + 1.975, 0)) < 0.3) // a marker: 1.975 over the captured full-size stand
                    .findFirst().orElse(null);
            assertNotNull(tag, "the countdown stand 1.38 below");
            assertEquals("[■■■■■]", PlainTextComponentSerializer.plainText().serialize(tag.getCustomName()));
            ticks(game, 5);
            assertEquals(0, gray(tag.getCustomName()), "the clock leaves the squares lit");
            fish.setHealth(4f);
            ticks(game, 1);
            assertEquals(2, gray(tag.getCustomName()), "half its health: two squares gray");
            game.state().set(Scope.GAME, Creatures.BEDBUG_LIFE, 3L);
            SilverfishEntity brief = creatures.bedbug(fa.player, new Pos(2.5, 65, 0.5));
            ticks(game, 5);
            assertTrue(brief.isRemoved(), "gone when its time is up");
        });
    }

    @Test
    void aDefenderCountsDown() {
        round("GolemA", (game, fa, fb) -> {
            Creatures creatures = game.module(Creatures.class);
            IronGolemEntity golem = creatures.defender(fa.player, new Pos(0.5, 65, 0.5));
            assertNotNull(golem);
            assertFalse(golem.isCustomNameVisible(), "the stand's line is its only tag: a name would sit on the timer");
            assertNotNull(golem);
            assertEquals(20.0f, golem.getHealth(), 0.01f);
            assertTrue(chat(fa).contains("Your creature will fight for you for 240 seconds."), chat(fa).toString());
            ticks(game, 2);
            Entity tag = game.instance().getEntities().stream()
                    .filter(e -> e.getEntityType() == EntityType.ARMOR_STAND
                            && e.getPosition().distance(golem.getPosition().add(0, 0.94 + 1.975, 0)) < 0.3) // not a shopkeeper's label
                    .findFirst().orElse(null);
            assertNotNull(tag, "the countdown stand over it");
            String text = PlainTextComponentSerializer.plainText().serialize(tag.getCustomName());
            assertTrue(text.startsWith("239.") && text.endsWith("s [■■■■■■■■■■]"), text);
            assertEquals(".9", Creatures.seconds(18), "the leading zero goes");
            assertEquals("-.1", Creatures.seconds(-2));
            assertEquals("240.0", Creatures.seconds(4800));
        });
    }

    @Test
    void aCreatureKillIsTheOwnersWithoutTheLoot() {
        round("BugKill", (game, fa, fb) -> {
            Player a = fa.player, b = fb.player;
            Creatures creatures = game.module(Creatures.class);
            SilverfishEntity fish = creatures.bedbug(a, new Pos(0.5, 65, 0.5));
            b.getInventory().setItemStack(3, ItemStack.of(Material.IRON_INGOT, 12));
            game.module(Respawns.class).unprotect(b);
            var services = Mechanics.polyp.services();
            services.damage().apply(MobDamage.INSTANCE.snapshot(fish, b, 100f, false, services));
            assertTrue(chat(fb).stream().anyMatch(l -> l.equals(b.getUsername() + " was killed by " + a.getUsername() + "'s Bedbug.")),
                    chat(fb).toString());
            assertEquals(0, count(a, Material.IRON_INGOT), "the owner is handed nothing");
        });
    }

    @Test
    void theOwnersShareIsARule() {
        round("BugPay", (game, fa, fb) -> {
            Player a = fa.player, b = fb.player;
            game.state().set(Scope.GAME, Spoils.TO_OWNER, KeySet.only(Material.IRON_INGOT.key()));
            SilverfishEntity fish = game.module(Creatures.class).bedbug(a, new Pos(0.5, 65, 0.5));
            b.getInventory().setItemStack(3, ItemStack.of(Material.IRON_INGOT, 12));
            game.module(Respawns.class).unprotect(b);
            var services = Mechanics.polyp.services();
            services.damage().apply(MobDamage.INSTANCE.snapshot(fish, b, 100f, false, services));
            assertEquals(12, count(a, Material.IRON_INGOT), "a mode may pay the owner");
        });
    }

    @Test
    void suddenDeathBringsADragonASide() {
        round("Dragons", (game, fa, fb) -> {
            Creatures creatures = game.module(Creatures.class);
            Dragons.suddenDeath(game);
            List<EnderDragonEntity> dragons = game.instance().getEntities().stream()
                    .filter(e -> e instanceof EnderDragonEntity).map(e -> (EnderDragonEntity) e).toList();
            assertEquals(2, dragons.size(), "one for each standing side");
            assertTrue(chat(fa).stream().anyMatch(l -> l.startsWith("SUDDEN DEATH: +1 ") && l.endsWith(" Dragon!")), chat(fa).toString());
            EnderDragonEntity first = dragons.getFirst();
            assertEquals(80.0, first.getPosition().y(), 0.01, "fifteen over the side's spawn");
            assertFalse(first.sides().same(first, fa.player) && first.sides().same(first, fb.player), "one side's, not both");
            Dragons.moreDragons(game);
            assertEquals(4, creatures.count(), "the wave adds one a side");
            assertTrue(chat(fa).contains("MORE DRAGONS HAVE SPAWNED!"), chat(fa).toString());
        });
    }

    private static int count(Player p, Material material) {
        int n = 0;
        for (ItemStack stack : p.getInventory().getItemStacks()) if (stack.material() == material) n += stack.amount();
        return n;
    }

    /** Captured: 56 of 56 bedbugs moved within 1.4 s of spawning (median 150 ms), an enemy in reach or not. */
    @Test
    void aBedbugWithNobodyNearStillMoves() {
        round("BugWander", (game, fa, fb) -> {
            // the deck's west edge: the owner 12 blocks off is no target, the enemy 27 blocks off is out of reach, and
            // half the wander's random cells lie past the edge, as on an island
            SilverfishEntity fish = game.module(Creatures.class).bedbug(fa.player, new Pos(-19.5, 65, 0.5));
            assertNotNull(fish);
            Pos from = fish.getPosition();
            double moved = 0;
            try {
                for (int tick = 1; tick <= 160 && moved < 0.5; tick++) {
                    ticks(game, 1);
                    fish.tick(System.nanoTime());
                    moved = fish.getPosition().distance(from);
                }
            } finally {
                fish.remove();
            }
            assertTrue(moved >= 0.5, "a bedbug with nobody in reach wanders within eight seconds, moved " + moved);
        });
    }

    /** Captured 2026-09-28: Hypixel's bedbugs never stood longer than 0.8 s; ours stood 1.3 to 5 s with their owner near. */
    @Test
    void aBedbugBesideItsOwnerKeepsMoving() {
        round("BugOwner", (game, fa, fb) -> {
            Pos beside = fa.player.getPosition().add(2, 0, 0);
            SilverfishEntity fish = game.module(Creatures.class).bedbug(fa.player, beside);
            assertNotNull(fish);
            Pos last = fish.getPosition();
            int still = 0, longest = 0;
            try {
                for (int tick = 1; tick <= 200; tick++) {
                    ticks(game, 1);
                    fish.tick(System.nanoTime());
                    Pos now = fish.getPosition();
                    still = now.distance(last) > 0.02 ? 0 : still + 1;
                    longest = Math.max(longest, still);
                    last = now;
                }
            } finally {
                fish.remove();
            }
            assertTrue(longest <= 20, "a bedbug with its owner beside it never stands a second, stood " + longest + " ticks");
        });
    }

    /** The user, 2026-09-28: a Hypixel bedbug with nobody in reach roams. The corner of the deck, void on two sides. */
    @Test
    void aBedbugAtTheEdgeKeepsMoving() {
        round("BugEdge", (game, fa, fb) -> {
            SilverfishEntity fish = game.module(Creatures.class).bedbug(fa.player, new Pos(-19.5, 65, -19.5));
            assertNotNull(fish);
            Pos last = fish.getPosition();
            int still = 0, longest = 0;
            try {
                for (int tick = 1; tick <= 300; tick++) {
                    ticks(game, 1);
                    fish.tick(System.nanoTime());
                    Pos now = fish.getPosition();
                    still = now.distance(last) > 0.02 ? 0 : still + 1;
                    longest = Math.max(longest, still);
                    last = now;
                }
            } finally {
                fish.remove();
            }
            assertTrue(longest <= 20, "a bedbug at the edge never stands a second, stood " + longest + " ticks");
        });
    }

    /** Captured: a killed bedbug flies, falls and fades for a second; only its tag and its count go at once. */
    @Test
    void aDeadBedbugKeepsItsCorpse() {
        round("BugCorpse", (game, fa, fb) -> {
            Creatures creatures = game.module(Creatures.class);
            SilverfishEntity fish = creatures.bedbug(fa.player, new Pos(0.5, 65, 0.5));
            assertNotNull(fish);
            try {
                assertFalse(fish.getActiveEffects().isEmpty(), "a bedbug wears its effects");
                fish.kill();
                ticks(game, 2);
                assertFalse(fish.isRemoved(), "the corpse stays for its animation");
                assertFalse(fish.getActiveEffects().isEmpty(), "and keeps its swirl");
                for (int i = 0; i < 25 && !fish.isRemoved(); i++) fish.tick(System.nanoTime());
                assertTrue(fish.isRemoved(), "and goes after its twenty ticks");
            } finally {
                fish.remove();
            }
        });
    }

    /** Captured 2026-09-28: bedbugs walk up to a wall and face the enemy behind it, sight or none. */
    @Test
    void aBedbugTargetsThroughAWall() {
        round("BugWall", (game, fa, fb) -> {
            for (int z = -2; z <= 2; z++) {
                for (int y = 65; y <= 67; y++) game.shard().setBlock(new BlockVec(4, y, z), Block.WHITE_WOOL);
            }
            SilverfishEntity fish = game.module(Creatures.class).bedbug(fa.player, new Pos(0.5, 65, 0.5)); // the enemy 7 blocks east, walled off
            assertNotNull(fish);
            try {
                for (int tick = 1; tick <= 40 && fish.attackTarget() != fb.player; tick++) {
                    ticks(game, 1);
                    fish.tick(System.nanoTime());
                }
                assertSame(fb.player, fish.attackTarget(), "the enemy behind the wall is its target");
                for (int tick = 1; tick <= 40; tick++) {
                    ticks(game, 1);
                    fish.tick(System.nanoTime());
                }
                assertTrue(fish.getPosition().x() > 2.0, "and it walks up to the wall: x " + fish.getPosition().x());
            } finally {
                fish.remove();
            }
        });
    }

    /** The user, 2026-09-28: a creature hunts an invisible player as any other; only the shopkeeper looks away. */
    @Test
    void aBedbugHuntsTheInvisible() {
        round("BugUnseen", (game, fa, fb) -> {
            fb.player.addEffect(new Potion(PotionEffect.INVISIBILITY, 0, 600, Potion.ICON_FLAG));
            fb.player.setInvisible(true);
            SilverfishEntity fish = game.module(Creatures.class).bedbug(fa.player, new Pos(0.5, 65, 0.5));
            assertNotNull(fish);
            try {
                for (int tick = 1; tick <= 40 && fish.attackTarget() != fb.player; tick++) {
                    ticks(game, 1);
                    fish.tick(System.nanoTime());
                }
                assertSame(fb.player, fish.attackTarget(), "the invisible enemy is its target");
            } finally {
                fish.remove();
            }
        });
    }

    /** The user, 2026-09-28: a defender fights players only, never a Bedbug or a dragon, not even back when one hurts it. */
    @Test
    void aDefenderFightsPlayersOnly() {
        round("GolemOnly", (game, fa, fb) -> {
            Creatures creatures = game.module(Creatures.class);
            fb.player.teleport(new Pos(-19.5, 65, -19.5)).join(); // past the golem's 16 blocks
            IronGolemEntity golem = creatures.defender(fa.player, new Pos(0.5, 65, 0.5));
            SilverfishEntity fish = creatures.bedbug(fb.player, new Pos(2.5, 65, 0.5));
            EnderDragonEntity dragon = EnderDragonEntity.spawn(Mechanics.mobs, game.shard(), new Pos(0.5, 72, 6.5));
            assertNotNull(golem);
            assertNotNull(fish);
            try {
                tickGolem(game, golem, 20);
                assertNotSame(fish, golem.attackTarget(), "an enemy's Bedbug is no target");
                assertNotSame(dragon, golem.attackTarget(), "nor a dragon");
                hit(golem, fish);
                tickGolem(game, golem, 15); // past the hurt window, so the next hit lands
                assertNotSame(fish, golem.attackTarget(), "not even back");
                hit(golem, dragon);
                tickGolem(game, golem, 15);
                assertNotSame(dragon, golem.attackTarget());
                fb.player.teleport(new Pos(3.5, 65, 0.5)).join();
                tickGolem(game, golem, 20);
                assertSame(fb.player, golem.attackTarget(), "an enemy player is");
            } finally {
                golem.remove();
                fish.remove();
                dragon.remove();
            }
        });
    }

    private static void tickGolem(Game game, IronGolemEntity golem, int n) {
        for (int tick = 1; tick <= n; tick++) {
            ticks(game, 1);
            golem.tick(System.nanoTime());
        }
    }

    // a creature's hit on the golem, through the damage pipeline
    private static void hit(IronGolemEntity golem, net.minestom.server.entity.LivingEntity by) {
        Mechanics.polyp.services().damage().apply(io.github.term4.polyp.mechanics.damage.DamageSnapshot.of(golem, MobDamage.INSTANCE)
                .withSource(by).withAmount(1f));
    }

    // the squares a tag draws gray, the health it lost
    private static int gray(Component text) {
        int n = 0;
        if (text instanceof TextComponent t && NamedTextColor.GRAY.equals(t.color())) {
            n += (int) t.content().chars().filter(ch -> ch == '■').count();
        }
        for (Component child : text.children()) n += gray(child);
        return n;
    }

    private static void round(String name, Body body) {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = Matches.launch(solo, Matches.platformFor(null), BedWarsRoundTest.fixture());
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
