package tennacle.games.bedwars;

import net.kyori.adventure.text.Component;
import net.minestom.server.component.DataComponents;
import tennacle.games.Keepers;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;
import tennacle.games.beds.Lives;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.combat.Respawns;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.world.WorldPolicy;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shopkeeper opens the item shop; purchases land where they belong and read as Hypixel's do. */
class ShopTest extends HeadlessServerTest {

    private static final Pos ROOM = new Pos(0.5, 100, 0.5);

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path replays;

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        tennacle.glue.ReplayBridge.install();
        tennacle.games.watch.Replays.install(replays, new ShardManager());
    }

    @Test
    void theKeeperSellsWool() {
        round("ShopA", (game, a) -> {
            Entity keeper = keeperOf(game, a);
            assertTrue(keeper.getTag(WorldPolicy.INERT), "scenery");
            assertFalse(WorldPolicy.canAffect(a, keeper));
            EventDispatcher.call(new PlayerEntityInteractEvent(a, keeper, PlayerHand.MAIN, Vec.ZERO));
            assertNotNull(Screens.screen(a), "a right click opens the shop");
            assertEquals(Material.WHITE_WOOL, Screens.inventory(a).getItemStack(19).material(), "Quick Buy's first pin");

            give(a, Material.IRON_INGOT, 10);
            click(a, 19, new Click.Left(19));
            ItemStack wool = find(a, "_wool");
            assertEquals(16, wool.amount());
            assertEquals(teamWool(game, a), wool.material(), "in the team's color");
            assertEquals(6, held(a, Material.IRON_INGOT));
            assertTrue(chat(a).contains("You purchased Wool"), chat(a).toString());

            click(a, 19, new Click.Left(19));
            click(a, 19, new Click.Left(19));
            assertTrue(chat(a).contains("You don't have enough Iron! Need 2 more!"), chat(a).toString());
        });
    }

    @Test
    void armorClimbsNeverFalls() {
        round("ShopB", (game, a) -> {
            ItemShop.open(game, a, Wares.Tab.ARMOR);
            give(a, Material.IRON_INGOT, 48);
            give(a, Material.GOLD_INGOT, 12);
            click(a, 19, new Click.Left(19));
            assertEquals(Material.CHAINMAIL_LEGGINGS, a.getEquipment(EquipmentSlot.LEGGINGS).material());
            assertEquals(Material.CHAINMAIL_BOOTS, a.getEquipment(EquipmentSlot.BOOTS).material());
            click(a, 19, new Click.Left(19));
            assertTrue(chat(a).contains("You've already purchased this item!"));
            click(a, 20, new Click.Left(20));
            assertEquals(Material.IRON_BOOTS, a.getEquipment(EquipmentSlot.BOOTS).material());
            click(a, 19, new Click.Left(19));
            assertTrue(chat(a).contains("You already have a higher tier item."));
            assertEquals(24, held(a, Material.IRON_INGOT), "a refusal takes nothing");
        });
    }

    @Test
    void toolsLoseATierAtDeath() {
        round("ShopC", (game, a) -> {
            ItemShop.open(game, a, Wares.Tab.TOOLS);
            give(a, Material.IRON_INGOT, 20);
            click(a, 20, new Click.Left(20));
            click(a, 20, new Click.Left(20));
            assertEquals(Material.IRON_PICKAXE, find(a, "_pickaxe").material(), "the second tier replaces the first");
            assertEquals(1, count(a, "_pickaxe"));
            assertTrue(chat(a).contains("You purchased Iron Pickaxe (Efficiency II)"), chat(a).toString());

            game.module(Lives.class).fell(a);
            Scope seat = Scope.slot(game.roster().slotOf(a).index());
            BedWarsRoundTest.tickUntil(game, "the respawn", () -> !game.state().get(seat, Respawns.DEAD));
            assertEquals(Material.WOODEN_PICKAXE, find(a, "_pickaxe").material(), "one tier down");
            assertEquals(1, game.state().get(seat, Gear.PICKAXE));
        });
    }

    @Test
    void aSwordTakesTheWoodensSlot() {
        round("ShopD", (game, a) -> {
            ItemShop.open(game, a, Wares.Tab.MELEE);
            give(a, Material.IRON_INGOT, 10);
            give(a, Material.GOLD_INGOT, 7);
            a.setHeldItemSlot((byte) 5);
            click(a, 19, new Click.Left(19));
            assertEquals(Material.STONE_SWORD, a.getInventory().getItemStack(0).material(), "the wooden one's slot, not the held one");
            assertEquals(0, count(a, "wooden_sword"), "the wooden one is gone");
            assertTrue(a.getInventory().getItemStack(0).has(net.minestom.server.component.DataComponents.UNBREAKABLE));

            click(a, 20, new Click.Left(20));
            // the user, 2026-09-28: a better sword takes the melee slot from the sword there, wherever the hand is
            assertEquals(Material.IRON_SWORD, a.getInventory().getItemStack(0).material(), "the melee slot, its stone sword beaten");
            assertEquals(Material.STONE_SWORD, a.getInventory().getItemStack(1).material(), "the beaten one to the first empty slot, the price paid");
        });
    }

    @Test
    void anItemIsPickedAsItsWare() {
        ItemStack sharpened = ItemStack.of(Material.STONE_SWORD).with(DataComponents.ENCHANTMENTS,
                new net.minestom.server.item.component.EnchantmentList(java.util.Map.of(net.minestom.server.item.enchant.Enchantment.SHARPNESS, 1)));
        assertEquals("stone-sword", Wares.pickOf(sharpened), "an upgrade leaves it the ware it was");
        assertEquals("wool", Wares.pickOf(ItemStack.of(Material.RED_WOOL, 16)), "any team's wool");
        assertEquals("bow", Wares.pickOf(ItemStack.of(Material.BOW)));
        ItemStack power = ItemStack.of(Material.BOW).with(DataComponents.ENCHANTMENTS,
                new net.minestom.server.item.component.EnchantmentList(java.util.Map.of(net.minestom.server.item.enchant.Enchantment.POWER, 1)));
        assertEquals("power-bow", Wares.pickOf(power), "a like item told apart by its icon");
        assertEquals("tnt", Wares.pickOf(ItemStack.of(Material.TNT)));
    }

    @Test
    void hotbarOrdersRouteWhatIsBought() {
        round("ShopH", (game, a) -> {
            var kits = game.module(io.github.term4.grotto.modules.gear.Kits.class);
            List<List<String>> main = new java.util.ArrayList<>(HotbarOrders.empty());
            main.set(0, List.of("melee"));
            main.set(3, List.of("ware:fireball", "utility"));
            main.set(4, List.of("utility"));
            List<List<String>> after = new java.util.ArrayList<>(HotbarOrders.empty());
            after.set(8, List.of("ware:tnt"));
            HotbarOrders.set(a, new HotbarOrders.Layouts(main, after));

            assertTrue(kits.give(a, "utility", ItemStack.of(Material.TNT)));
            assertTrue(kits.give(a, "utility", ItemStack.of(Material.FIRE_CHARGE)));
            assertEquals(Material.TNT, a.getInventory().getItemStack(4).material(), "first in slot 5 as a utility item");
            assertEquals(Material.FIRE_CHARGE, a.getInventory().getItemStack(3).material(), "first in slot 4 as itself");

            game.state().set(Scope.team(Match.teamOf(game, a)), tennacle.games.beds.Beds.STANDS, false);
            kits.rearrange(a);
            assertEquals(Material.TNT, a.getInventory().getItemStack(8).material(), "the after-bed layout takes over");
        });
    }

    @Test
    void aPickedUpSwordTakesTheWoodensSlot() {
        round("ShopF", (game, a) -> {
            a.getInventory().setItemStack(1, ItemStack.of(Material.IRON_INGOT, 5));
            var dropped = io.github.term4.polyp.entity.DroppedItemEntity.spawn(game.shard(), a.getPosition(), Vec.ZERO,
                    ItemStack.of(Material.STONE_SWORD), null, 0);
            assertNotNull(dropped);
            EventDispatcher.call(new net.minestom.server.event.item.PickupItemEvent(a, dropped));
            assertEquals(Material.STONE_SWORD, a.getInventory().getItemStack(0).material());
            assertEquals(0, count(a, "wooden_sword"));
            assertEquals(1, count(a, "stone_sword"));
        });
    }

    @Test
    void quickBuyPinsAndHotbarSlots() {
        round("ShopE", (game, a) -> {
            ItemShop.open(game, a, Wares.Tab.BLOCKS);
            click(a, 20, new Click.LeftShift(20)); // Hardened Clay
            assertEquals("Adding to Quick Buy...", title(a));
            click(a, 43, new Click.Left(43));
            assertEquals("Quick Buy", title(a));
            assertEquals(Material.TERRACOTTA, Screens.inventory(a).getItemStack(43).material());
            click(a, 43, new Click.LeftShift(43));
            assertEquals(Material.RED_STAINED_GLASS_PANE, Screens.inventory(a).getItemStack(43).material(), "unpinned");

            ItemShop.open(game, a, Wares.Tab.BLOCKS);
            List<Component> woolLore = Screens.inventory(a).getItemStack(19).get(DataComponents.LORE);
            assertTrue(woolLore.stream().map(l -> PlainTextComponentSerializer.plainText().serialize(l))
                    .anyMatch("Shift Click to add to Quick Buy"::equals), "any ware offers the pin: " + woolLore);
            click(a, 19, new Click.LeftShift(19)); // Wool, pinned already
            assertEquals("Adding to Quick Buy...", title(a), "a pinned ware pins again");
            click(a, 42, new Click.Left(42));
            assertEquals("Quick Buy", title(a));
            assertTrue(Screens.inventory(a).getItemStack(42).material().key().value().endsWith("_wool"), "into the slot picked");

            HotbarManager.open(game, a);
            click(a, 10, new Click.Left(10)); // Blocks onto the cursor
            click(a, 27 + 5, new Click.Left(27 + 5));
            assertEquals(5, BedWars.KIT.slotFor(game, a, "blocks"));
            ItemShop.open(game, a, Wares.Tab.QUICK_BUY);
            give(a, Material.IRON_INGOT, 4);
            click(a, 19, new Click.Left(19));
            assertTrue(a.getInventory().getItemStack(5).material().key().value().endsWith("_wool"), "the manager's slot");

            give(a, Material.IRON_INGOT, 4);
            click(a, 19, new Click.HotbarSwap(7, 19));
            assertTrue(a.getInventory().getItemStack(7).material().key().value().endsWith("_wool"), "a number key's slot");
        });
    }

    @Test
    void ownInventoryStaysUsable() {
        round("ShopG", (game, a) -> {
            ItemShop.open(game, a, Wares.Tab.BLOCKS);
            assertFalse(ownClick(a, new Click.Left(5)), "their own inventory");
            assertTrue(ownClick(a, new Click.LeftShift(5)), "nothing crosses into the shop");
            HotbarManager.open(game, a);
            assertFalse(ownClick(a, new Click.Left(5)));
            click(a, 10, new Click.Left(10)); // Blocks onto the cursor
            assertTrue(ownClick(a, new Click.Left(5)), "a held category stays out of it");
        });
    }

    // true = refused
    private static boolean ownClick(Player p, Click click) {
        var event = new InventoryPreClickEvent(p.getInventory(), p, click);
        EventDispatcher.call(event);
        return event.isCancelled();
    }

    @Test
    void speedLooksLikeHypixels() {
        var contents = Wares.of("speed").icons().getFirst().apply(null, null).get(net.minestom.server.component.DataComponents.POTION_CONTENTS);
        assertEquals(net.minestom.server.potion.PotionType.FIRE_RESISTANCE, contents.potion(), "373:8195 on a 1.8 client");
    }

    // a live Solo game with a foe seated, the viewer holding nothing but the kit
    /** The user, 2026-09-28: a shopkeeper turns to the nearest player at any distance, all the way round. */
    @Test
    void aKeeperTurnsFromAnyDistance() {
        round("ShopI", (game, a) -> {
            Entity keeper = keeperOf(game, a);
            float facing = keeper.getPosition().yaw();
            a.teleport(keeper.getPosition().add(25, 0, 0).withView(0, 0)).join(); // the far side of the deck, off the spawn's line
            long until = game.tick() + 3;
            BedWarsRoundTest.tickUntil(game, "three looks", () -> game.tick() >= until);
            assertNotEquals(facing, keeper.getPosition().yaw(), 0.5f, "turned to a player 25 blocks off");
        });
    }

    /** The user, 2026-09-28: a shopkeeper never turns to an invisible player, unless the rule lets it give them away. */
    @Test
    void aKeeperLooksPastTheInvisible() {
        round("ShopH", (game, a) -> {
            Entity keeper = keeperOf(game, a);
            a.teleport(keeper.getPosition().add(5, 0, 0).withView(0, 0)).join(); // five blocks east, off the spawn's line
            a.addEffect(new Potion(PotionEffect.INVISIBILITY, 0, 600, Potion.ICON_FLAG));
            a.setInvisible(true);
            float towardA = keeper.getPosition().withLookAt(a.getPosition()).yaw();
            long until = game.tick() + 3;
            BedWarsRoundTest.tickUntil(game, "three looks", () -> game.tick() >= until);
            assertTrue(Math.abs(turn(keeper.getPosition().yaw(), towardA)) > 5, "the nearest it sees is someone else: yaw " + keeper.getPosition().yaw() + " toward a " + towardA);
            game.state().set(Scope.GAME, Keepers.LOOK_INVISIBLE, true);
            long later = game.tick() + 3;
            BedWarsRoundTest.tickUntil(game, "three more", () -> game.tick() >= later);
            assertEquals(0, turn(keeper.getPosition().yaw(), towardA), 1.0f, "the rule lets it turn to them, the nearest");
        });
    }

    private static void round(String name, java.util.function.BiConsumer<Game, Player> body) {
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
            CHAT.set(a);
            body.accept(game, a.player);
        } finally {
            CHAT.remove();
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            a.player.remove();
            b.player.remove();
        }
    }

    private static final ThreadLocal<FakePlayer> CHAT = new ThreadLocal<>();

    private static List<String> chat(Player p) {
        return CHAT.get().sent(SystemChatPacket.class).stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }

    private static String title(Player p) {
        Inventory open = Screens.inventory(p);
        return open == null ? "" : PlainTextComponentSerializer.plainText().serialize(open.getTitle());
    }

    private static float turn(float yaw, float toward) {
        float d = (yaw - toward) % 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    private static Entity keeperOf(Game game, Player p) {
        String team = Match.teamOf(game, p);
        for (var entry : game.module(Keepers.class).standing().entrySet()) {
            if (entry.getValue().group().equals(team) && entry.getValue().key().equals("bedwars:shop")) return entry.getKey();
        }
        throw new AssertionError("no keeper for " + team);
    }

    private static Material teamWool(Game game, Player p) {
        return game.roster().team(Match.teamOf(game, p)).livery().get(Wares.WOOL).material();
    }

    private static void click(Player p, int slot, Click click) {
        Inventory open = Screens.inventory(p);
        assertNotNull(open, "a screen is open");
        EventDispatcher.call(new InventoryPreClickEvent(open, p, click));
    }

    private static void give(Player p, Material material, int amount) {
        p.getInventory().addItemStack(ItemStack.of(material, amount));
    }

    private static int held(Player p, Material material) {
        int n = 0;
        for (int i = 0; i < 36; i++) if (p.getInventory().getItemStack(i).material() == material) n += p.getInventory().getItemStack(i).amount();
        return n;
    }

    private static ItemStack find(Player p, String suffix) {
        for (int i = 0; i < 36; i++) {
            ItemStack in = p.getInventory().getItemStack(i);
            if (in.material().key().value().endsWith(suffix)) return in;
        }
        return ItemStack.AIR;
    }

    private static int count(Player p, String suffix) {
        int n = 0;
        for (int i = 0; i < 36; i++) if (p.getInventory().getItemStack(i).material().key().value().endsWith(suffix)) n++;
        return n;
    }

}
