package tennacle.games.bedwars;

import tennacle.boot.Mechanics;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;
import tennacle.games.lives.Lives;
import tennacle.games.lives.Spoils;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.combat.KillCredit;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.MechanicsKeys;
import io.github.term4.polyp.MechanicsProfile;
import io.github.term4.polyp.Polyp;
import io.github.term4.polyp.config.KeySet;
import io.github.term4.polyp.entity.DroppedItemEntity;
import io.github.term4.polyp.mechanics.containers.Spill;
import io.github.term4.polyp.mechanics.damage.DamageSnapshot;
import io.github.term4.polyp.mechanics.damage.DeathConfig;
import io.github.term4.polyp.mechanics.damage.types.melee.MeleeDamage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import net.minestom.server.utils.inventory.PlayerInventoryUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A kill hands the dead's resources to the killer, the cursor and crafting grid included; a ruleset picks what else. */
class LootTest extends HeadlessServerTest {

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
    void theKillerTakesTheResources() {
        round("LootA", (game, fa, fb) -> {
            Player a = fa.player, b = fb.player;
            a.getInventory().setItemStack(1, ItemStack.of(Material.IRON_INGOT, 20));
            a.getInventory().setCursorItem(ItemStack.of(Material.GOLD_INGOT, 3));
            a.getInventory().setItemStack(PlayerInventoryUtils.CRAFT_SLOT_1, ItemStack.of(Material.DIAMOND, 2));
            a.getInventory().setItemStack(2, ItemStack.of(Material.STONE_SWORD));
            game.module(KillCredit.class).record(a, b, game.tick());
            fb.sent.clear();
            game.module(Lives.class).fell(a);
            assertEquals(20, Resources.IRON.held(b));
            assertEquals(3, Resources.GOLD.held(b), "the cursor hides nothing");
            assertEquals(2, Resources.DIAMOND.held(b), "nor the crafting grid");
            assertEquals(0, count(b, Material.STONE_SWORD), "resources only");
            assertEquals(List.of("+20 Iron", "+3 Gold", "+2 Diamonds"), loot(fb));
        });
    }

    @Test
    void aRulesetChoosesWhatMoves() {
        round("LootC", (game, fa, fb) -> {
            Player a = fa.player, b = fb.player;
            game.state().set(Scope.GAME, Spoils.TO_KILLER, KeySet.parse("except(minecraft:cobblestone)"));
            polyp().profiles().setPlayer(a, MechanicsProfile.builder()
                    .set(MechanicsKeys.DEATH, DeathConfig.builder().spill(Spill.DROP).build()).build());
            a.getInventory().setItemStack(1, ItemStack.of(Material.STONE_SWORD));
            a.getInventory().setItemStack(2, ItemStack.of(Material.COBBLESTONE, 8));
            Pos fell = a.getPosition();
            game.module(KillCredit.class).record(a, b, game.tick());
            game.module(Lives.class).fell(a);
            polyp().profiles().setPlayer(a, null);
            assertEquals(1, count(b, Material.STONE_SWORD), "everything but the cobblestone to the killer");
            assertEquals(0, count(b, Material.COBBLESTONE));
            BedWarsRoundTest.tickUntil(game, "the cobblestone where they fell", () -> lying(game, fell, Material.COBBLESTONE) == 8);
        });
    }

    @Test
    void aDeadKillerIsPaidAtTheRespawn() {
        round("LootB", (game, fa, fb) -> {
            Player a = fa.player, b = fb.player;
            Respawns respawns = game.module(Respawns.class);
            game.module(Lives.class).fell(b);
            assertTrue(respawns.isDead(b));
            a.getInventory().setItemStack(1, ItemStack.of(Material.EMERALD, 4));
            game.module(KillCredit.class).record(a, b, game.tick());
            game.module(Lives.class).fell(a);
            assertEquals(0, Resources.EMERALD.held(b), "not into the wait's hands");
            BedWarsRoundTest.tickUntil(game, "the respawn", () -> !respawns.isDead(b));
            assertEquals(4, Resources.EMERALD.held(b));
        });
    }

    /** A kept item skips the killer's share and the wipe, back in its slot at the respawn; set for one player here. */
    @Test
    void keptItemsRideTheRespawn() {
        round("LootK", (game, fa, fb) -> {
            Player a = fa.player, b = fb.player;
            Respawns respawns = game.module(Respawns.class);
            game.state().set(Scope.slot(game.roster().slotOf(a).index()), Lives.KEEP, KeySet.only(Material.ENDER_PEARL.key()));
            a.getInventory().setItemStack(5, ItemStack.of(Material.ENDER_PEARL, 2));
            a.getInventory().setItemStack(1, ItemStack.of(Material.IRON_INGOT, 20));
            game.module(KillCredit.class).record(a, b, game.tick());
            game.module(Lives.class).fell(a);
            assertEquals(20, Resources.IRON.held(b), "the rest is still the killer's");
            assertEquals(0, count(b, Material.ENDER_PEARL));
            BedWarsRoundTest.tickUntil(game, "the respawn", () -> !respawns.isDead(a));
            assertEquals(ItemStack.of(Material.ENDER_PEARL, 2), a.getInventory().getItemStack(5), "back where it was");
            assertEquals(0, count(a, Material.IRON_INGOT));
        });
    }

    @Test
    void aSlowingItemLengthensTheWait() {
        round("LootS", (game, fa, fb) -> {
            Player a = fa.player, b = fb.player;
            Respawns respawns = game.module(Respawns.class);
            game.state().set(Scope.GAME, Lives.SLOW_ITEMS, KeySet.only(Material.GOLDEN_SWORD.key()));
            game.state().set(Scope.GAME, Lives.SLOW_TICKS, 60L);
            long plain = waited(game, respawns, a, b, Material.IRON_SWORD);
            long slowed = waited(game, respawns, a, b, Material.GOLDEN_SWORD);
            assertEquals(60, slowed - plain, 2, plain + " then " + slowed);
        });
    }

    private static long waited(Game game, Respawns respawns, Player killer, Player victim, Material weapon) {
        BedWarsRoundTest.tickUntil(game, "out of spawn protection", () -> !respawns.isProtected(victim));
        long settled = game.tick() + 20; // past the last hit's immunity
        BedWarsRoundTest.tickUntil(game, "a second", () -> game.tick() >= settled);
        Mechanics.polyp.services().damage().apply(DamageSnapshot.of(victim, MeleeDamage.INSTANCE).withSource(killer)
                .withItem(ItemStack.of(weapon)).withAmount(100f));
        assertTrue(respawns.isDead(victim), "dead to the " + weapon);
        long from = game.tick();
        BedWarsRoundTest.tickUntil(game, "the respawn", () -> !respawns.isDead(victim));
        return game.tick() - from;
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
            body.run(game, a, b);
        } finally {
            tennacle.games.watch.Replays.discard(game); // no replay still writing into the temp dir as it goes
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }

    private static Polyp polyp() {
        return Polyp.getInstance();
    }

    private static int count(Player p, Material material) {
        int n = 0;
        for (int i = 0; i < 36; i++) if (p.getInventory().getItemStack(i).material() == material) n += p.getInventory().getItemStack(i).amount();
        return n;
    }

    private static int lying(Game game, Pos at, Material material) {
        return game.instance().getEntities().stream()
                .filter(e -> e instanceof DroppedItemEntity item && item.getItemStack().material() == material
                        && e.getPosition().distance(at) <= 4)
                .mapToInt(e -> ((DroppedItemEntity) e).getItemStack().amount()).sum();
    }

    private static List<String> loot(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message()))
                .filter(line -> line.startsWith("+")).toList();
    }
}
