package tennacle.games.bedwars;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import tennacle.games.shop.ItemCurrency;
import tennacle.games.shop.Shop;
import tennacle.games.shop.Sale;
import tennacle.games.shop.Refusal;
import tennacle.games.shop.Price;
import tennacle.games.shop.Offer;
import tennacle.games.Keepers;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.EnchantmentList;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.network.packet.server.play.SetTitleTextPacket;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The upgrades keeper's window: each upgrade lands on the team, and the traps go off on the way in. */
class UpgradesTest extends HeadlessServerTest {

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
    void upgradesLandOnTheTeam() {
        round("UpgA", (game, fa, fb) -> {
            Player a = fa.player;
            EventDispatcher.call(new PlayerEntityInteractEvent(a, keeper(game, a), PlayerHand.MAIN, Vec.ZERO));
            assertEquals("Upgrades & Traps", title(a));
            give(a, Material.DIAMOND, 30);
            assertEquals(1, Screens.inventory(a).getItemStack(11).amount(), "the tier on sale, as Hypixel counts it (captured)");
            assertEquals(1, Screens.inventory(a).getItemStack(10).amount(), "a single tier shows one");
            click(a, 11); // Reinforced Armor
            ItemStack helmet = a.getEquipment(EquipmentSlot.HELMET);
            assertEquals(1, helmet.get(DataComponents.ENCHANTMENTS, EnchantmentList.EMPTY).level(Enchantment.PROTECTION));
            assertTrue(chat(fa).contains(a.getUsername() + " purchased Reinforced Armor I"), chat(fa).toString());
            UpgradeShop.open(game, a);
            assertEquals(2, Screens.inventory(a).getItemStack(11).amount(), "tier II on sale now");

            click(a, 11);
            assertTrue(chat(fa).contains("Clicking too fast!"), "a second click at once");
            assertNull(Screens.screen(a), "and the window shuts");
            ticks(game, 6);
            UpgradeShop.open(game, a);
            click(a, 11);
            assertEquals(2, a.getEquipment(EquipmentSlot.BOOTS).get(DataComponents.ENCHANTMENTS, EnchantmentList.EMPTY)
                    .level(Enchantment.PROTECTION), "tier II on every piece");

            ticks(game, 6);
            click(a, 10); // Sharpened Swords
            assertEquals(1, a.getInventory().getItemStack(0).get(DataComponents.ENCHANTMENTS, EnchantmentList.EMPTY)
                    .level(Enchantment.SHARPNESS), "the held sword");
            ticks(game, 6);
            click(a, 12); // Maniac Miner
            assertTrue(a.hasEffect(PotionEffect.HASTE));
            ticks(game, 6);
            click(a, 19); // the forge
            assertEquals(1, game.state().get(Scope.team(Match.teamOf(game, a)), Forges.LEVEL));

            give(a, Material.IRON_INGOT, 10);
            ItemShop.open(game, a, Wares.Tab.MELEE);
            List<String> lore = lore(Screens.inventory(a).getItemStack(19));
            assertTrue(lore.contains("Upgraded: Sharpness I"), lore.toString());
            click(a, 19); // Stone Sword
            ItemStack stone = a.getInventory().getItemStack(0);
            assertEquals(Material.STONE_SWORD, stone.material());
            assertEquals(1, stone.get(DataComponents.ENCHANTMENTS, EnchantmentList.EMPTY).level(Enchantment.SHARPNESS),
                    "a sword bought after it comes with it");
        });
    }

    @Test
    void theHealPoolHealsInside() {
        round("UpgB", (game, fa, fb) -> {
            Player a = fa.player;
            Pos spawn = game.roster().slotOf(a).spawn();
            EventDispatcher.call(new PlayerMoveEvent(a, spawn.add(0, 0, 1), true));
            assertFalse(a.hasEffect(PotionEffect.REGENERATION), "no pool yet");
            give(a, Material.DIAMOND, 1);
            UpgradeShop.open(game, a);
            click(a, 20);
            assertTrue(a.hasEffect(PotionEffect.REGENERATION), "bought while inside");
            a.teleport(spawn.add(0, 0, 1)).join();
            EventDispatcher.call(new PlayerMoveEvent(a, spawn.add(30, 0, 0), true));
            assertFalse(a.hasEffect(PotionEffect.REGENERATION), "gone outside");
        });
    }

    @Test
    void aTrapGoesOffOnTheWayIn() {
        round("UpgC", (game, fa, fb) -> {
            Player a = fa.player, b = fb.player;
            give(a, Material.DIAMOND, 7);
            UpgradeShop.open(game, a);
            click(a, 23); // Miner Fatigue
            ticks(game, 6);
            click(a, 14); // Blindness, 2 now
            ticks(game, 6);
            click(a, 16); // Reveal, 4 now
            assertEquals(3, Traps.queue(game, Match.teamOf(game, a)).size());
            assertEquals(0, held(a, Material.DIAMOND), "1 + 2 + 4");
            give(a, Material.DIAMOND, 8);
            ticks(game, 6);
            click(a, 15);
            assertTrue(chat(fa).contains("Trap queue full!"));

            Pos bed = new Pos(-9.5, 65, 0.5); // beside team 1's bed head
            String owner = Match.teamOf(game, a);
            assertEquals("1", owner, "the fixture's first side");
            fa.sent.clear();
            EventDispatcher.call(new PlayerMoveEvent(b, bed, true));
            assertTrue(b.hasEffect(PotionEffect.MINING_FATIGUE), "the head of the queue, on the intruder");
            assertTrue(titles(fa).contains("TRAP TRIGGERED!"));
            assertEquals(2, Traps.queue(game, owner).size());

            b.teleport(bed).join();
            ticks(game, 20);
            assertFalse(b.hasEffect(PotionEffect.BLINDNESS), "the next waits out the cooldown");
            ticks(game, 600);
            assertTrue(b.hasEffect(PotionEffect.BLINDNESS), "and goes off on whoever is still inside");
        });
    }

    /** The user, 2026-10-18 on Solace: a trap bought went off at once on a foe standing still in their own base. */
    @Test
    void aTrapIgnoresWhoWasSentAway() {
        round("UpgT", (game, fa, fb) -> {
            Player a = fa.player, b = fb.player;
            Pos bed = new Pos(-9.5, 65, 0.5); // beside team 1's bed head
            EventDispatcher.call(new PlayerMoveEvent(b, bed, true)); // in while nothing is queued
            b.teleport(new Pos(9.5, 65, 0.5)).join(); // sent home: a respawn, then standing still
            EventDispatcher.call(new net.minestom.server.event.player.PlayerTickEvent(b)); // the harness ticks no players
            give(a, Material.DIAMOND, 1);
            UpgradeShop.open(game, a);
            click(a, 14); // Blindness
            assertEquals(1, Traps.queue(game, Match.teamOf(game, a)).size(), "nobody inside: it waits");
            assertFalse(b.hasEffect(PotionEffect.BLINDNESS));
        });
    }

    /**
     * The user, 2026-10-18 on Solace: the first to join stood at red's seat until the draw made them blue, standing
     * still all game, and red's first trap went off on them at once. The draw is a reseat, done here by hand.
     */
    @Test
    void aTrapIgnoresTheDrawnFoe() {
        Game game = BedWarsRoundTest.byArrival(Matches.launch(Matches.mode("bedwars-solo"), Matches.platformFor(null), BedWarsRoundTest.fixture()));
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer a = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "DrawnA");
        FakePlayer b = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "DrawnB");
        try {
            assertTrue(Matches.enter(game, List.of(a.player)));
            assertTrue(Matches.enter(game, List.of(b.player)));
            BedWarsRoundTest.tickUntil(game, "both seated", () -> Game.of(a.player) == game && Game.of(b.player) == game);
            game.reseat(java.util.Map.of(a.player, "2", b.player, "1")); // the draw: red's first seat goes to blue
            BedWarsRoundTest.live(game);
            Player red = b.player, blue = a.player;
            assertEquals("2", Match.teamOf(game, blue));
            for (Player p : List.of(red, blue)) EventDispatcher.call(new net.minestom.server.event.player.PlayerTickEvent(p)); // the harness ticks no players
            give(red, Material.DIAMOND, 1);
            UpgradeShop.open(game, red);
            click(red, 14); // Blindness
            assertEquals(1, Traps.queue(game, "1").size(), "nobody at red's bed: it waits");
            assertFalse(blue.hasEffect(PotionEffect.BLINDNESS), "blue stands at home");
        } finally {
            tennacle.games.watch.Replays.discard(game);
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }

    @Test
    void magicMilkKeepsTrapsOff() {
        round("UpgD", (game, fa, fb) -> {
            Player a = fa.player, b = fb.player;
            give(a, Material.DIAMOND, 1);
            UpgradeShop.open(game, a);
            click(a, 14); // Blindness
            String owner = Match.teamOf(game, a);
            game.module(MagicMilk.class).drank(b);
            Pos bed = new Pos(-9.5, 65, 0.5);
            b.teleport(bed).join();
            EventDispatcher.call(new PlayerMoveEvent(b, bed, true));
            assertEquals(1, Traps.queue(game, owner).size(), "no trap takes the drinker");
            assertFalse(b.hasEffect(PotionEffect.BLINDNESS));

            fb.sent.clear();
            ticks(game, 601);
            assertTrue(chat(fb).contains("Your Magic Milk wore off!"), chat(fb).toString());
            assertTrue(b.hasEffect(PotionEffect.BLINDNESS), "still inside: the trap goes off at once");
            assertEquals(0, Traps.queue(game, owner).size());
        });
    }

    @Test
    void magicMilkKeepsYourOwnEffects() {
        round("UpgE", (game, fa, fb) -> {
            Player b = fb.player;
            for (PotionEffect kept : List.of(PotionEffect.SPEED, PotionEffect.INVISIBILITY, PotionEffect.JUMP_BOOST,
                    PotionEffect.ABSORPTION, PotionEffect.REGENERATION, PotionEffect.HASTE)) {
                b.addEffect(new Potion(kept, 1, 600));
            }
            b.addEffect(new Potion(PotionEffect.MINING_FATIGUE, 0, 160));
            game.module(MagicMilk.class).drank(b);
            assertFalse(b.hasEffect(PotionEffect.MINING_FATIGUE), "a trap's");
            for (PotionEffect kept : List.of(PotionEffect.SPEED, PotionEffect.INVISIBILITY, PotionEffect.JUMP_BOOST,
                    PotionEffect.ABSORPTION, PotionEffect.REGENERATION, PotionEffect.HASTE)) {
                assertTrue(b.hasEffect(kept), kept + " stays");
            }
        });
    }

    /** A trap price list emptied by hand reads as its captured default instead of breaking the window. */
    @Test
    void anEmptiedPriceIsTheDefault() {
        round("UpgE", (game, fa, fb) -> {
            Player a = fa.player;
            game.state().set(Scope.GAME, Traps.PRICES, List.of());
            give(a, Material.DIAMOND, 1);
            UpgradeShop.open(game, a);
            click(a, 14); // Blindness
            assertEquals(1, Traps.queue(game, Match.teamOf(game, a)).size());
            assertEquals(0, held(a, Material.DIAMOND), "the default's first tier");
        });
    }

    /** The shop hands the item over before it takes the money: a grant that fails costs nothing. */
    @Test
    void aFailedGrantIsFree() {
        round("UpgF", (game, fa, fb) -> {
            Player a = fa.player;
            give(a, Material.IRON_INGOT, 10);
            Offer broken = new Offer() {
                @Override public @NotNull String id() { return "test:broken"; }
                @Override public @NotNull Price price(@NotNull Game g, @NotNull Player buyer) {
                    return Price.of(4, ItemCurrency.of(Material.IRON_INGOT));
                }
                @Override public @Nullable Refusal refusal(@NotNull Game g, @NotNull Player buyer) { return null; }
                @Override public boolean grant(@NotNull Game g, @NotNull Player buyer) { return false; }
            };
            assertInstanceOf(Sale.Refused.class, game.module(Shop.class).buy(a, broken));
            assertEquals(10, held(a, Material.IRON_INGOT), "nothing taken");
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
            a.sent.clear();
            body.run(game, a, b);
        } finally {
            tennacle.games.watch.Replays.discard(game); // no replay still writing into the temp dir as it goes
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }

    private static Entity keeper(Game game, Player p) {
        String team = Match.teamOf(game, p);
        for (var entry : game.module(Keepers.class).standing().entrySet()) {
            var at = entry.getValue();
            if (at.group().equals(team) && at.key().equals("bedwars:upgrades")) return entry.getKey();
        }
        throw new AssertionError("no upgrades keeper for " + team);
    }

    private static void click(Player p, int slot) {
        Inventory open = Screens.inventory(p);
        assertNotNull(open, "a screen is open");
        EventDispatcher.call(new InventoryPreClickEvent(open, p, new Click.Left(slot)));
    }

    // n scheduler ticks straight through: a 600-tick wait under tickUntil's 8 s deadline hangs on Windows' timer
    // whenever no process holds it at 1 ms (a game client does, so it passed only while one ran)
    private static void ticks(Game game, int n) {
        long until = game.tick() + n;
        for (int i = 0; i < n * 2 && game.tick() < until; i++) {
            MinecraftServer.getSchedulerManager().processTick();
            MinecraftServer.getSchedulerManager().processTickEnd();
        }
        assertTrue(game.tick() >= until, n + " ticks");
    }

    private static String title(Player p) {
        Inventory open = Screens.inventory(p);
        return open == null ? "" : PlainTextComponentSerializer.plainText().serialize(open.getTitle());
    }

    private static List<String> chat(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }

    private static List<String> titles(FakePlayer p) {
        return p.sent(SetTitleTextPacket.class).stream()
                .map(t -> PlainTextComponentSerializer.plainText().serialize(t.title())).toList();
    }

    private static List<String> lore(ItemStack item) {
        return item.get(DataComponents.LORE, List.of()).stream()
                .map(l -> PlainTextComponentSerializer.plainText().serialize(l)).toList();
    }

    private static void give(Player p, Material material, int amount) {
        p.getInventory().addItemStack(ItemStack.of(material, amount));
    }

    private static int held(Player p, Material material) {
        int n = 0;
        for (int i = 0; i < 36; i++) if (p.getInventory().getItemStack(i).material() == material) n += p.getInventory().getItemStack(i).amount();
        return n;
    }
}
