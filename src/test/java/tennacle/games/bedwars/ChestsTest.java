package tennacle.games.bedwars;

import tennacle.boot.Mechanics;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;
import tennacle.games.beds.Beds;
import tennacle.games.beds.Lives;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import io.github.term4.polyp.entity.DroppedItemEntity;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockInteractEvent;
import net.minestom.server.event.player.PlayerStartDiggingEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.EnchantmentList;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A base's chests: the team chest shut to other sides, a punch putting a stack in, upgrades reaching what is stored. */
class ChestsTest extends HeadlessServerTest {

    private static final Pos ROOM = new Pos(0.5, 100, 0.5);
    // in the fixture's first base
    private static final BlockVec CHEST = new BlockVec(-12, 65, 2);
    private static final BlockVec ENDER = new BlockVec(-12, 65, -2);

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
    void aTeamChestIsTheSides() {
        round("ChestA", (game, fa, fb) -> {
            Player a = fa.player, b = fb.player;
            open(game, b, CHEST);
            assertNull(b.getOpenInventory(), "shut to another side");
            assertTrue(chat(fb).stream().anyMatch(l -> l.startsWith("You cannot open this Chest as the ")
                    && l.endsWith(" Team has not been eliminated!")), chat(fb).toString());

            open(game, a, CHEST);
            assertNotNull(a.getOpenInventory(), "open to its own");
            a.getInventory().setItemStack(1, ItemStack.of(Material.WOODEN_PICKAXE));
            a.getInventory().setItemStack(2, ItemStack.of(Material.OAK_PLANKS, 16));
            assertTrue(shift(a, 1).isCancelled(), "a tool stays out");
            assertFalse(shift(a, 2).isCancelled());
        });
    }

    /** The user, 2026-09-28: a number key over a better sword in the chest brings it to that slot and the wooden one
     *  goes, nothing into the chest; the container's refusal of the wooden one does not stop it. */
    @Test
    void aNumberKeyTakesTheBetterSword() {
        round("ChestG", (game, fa, fb) -> {
            Player a = fa.player;
            open(game, a, CHEST);
            var window = a.getOpenInventory();
            assertNotNull(window, "open to its own");
            window.setItemStack(3, ItemStack.of(Material.STONE_SWORD));
            InventoryPreClickEvent e = new InventoryPreClickEvent(window, a, new Click.HotbarSwap(0, 3));
            EventDispatcher.call(e);
            assertTrue(e.isCancelled(), "the kit's, not the click's");
            assertEquals(Material.STONE_SWORD, a.getInventory().getItemStack(0).material(), "the better sword in the keyed slot");
            for (int i = 0; i < 36; i++) assertNotEquals(Material.WOODEN_SWORD, a.getInventory().getItemStack(i).material(), "the wooden one is gone");
            assertTrue(window.getItemStack(3).isAir(), "and the chest slot empty");
        });
    }

    @Test
    void aPunchPutsTheStackIn() {
        round("ChestB", (game, fa, fb) -> {
            Player a = fa.player, b = fb.player;
            a.getInventory().setItemStack(1, ItemStack.of(Material.IRON_INGOT, 20));
            a.setHeldItemSlot((byte) 1);
            punch(game, a, CHEST);
            assertEquals(20, stored(game, a, CHEST, Material.IRON_INGOT));
            assertTrue(chat(fa).stream().anyMatch(l -> l.startsWith("Deposited x20 ")
                    && l.endsWith(" into Team Chest! (20 Total)")), chat(fa).toString());

            a.getInventory().setItemStack(1, ItemStack.of(Material.GOLD_INGOT, 5));
            a.getInventory().setItemStack(20, ItemStack.of(Material.GOLD_INGOT, 7));
            a.setSneaking(true);
            punch(game, a, CHEST);
            a.setSneaking(false);
            assertEquals(12, stored(game, a, CHEST, Material.GOLD_INGOT), "a sneak takes every stack of it");

            fa.sent.clear();
            a.getInventory().setItemStack(1, ItemStack.of(Material.WOODEN_PICKAXE));
            punch(game, a, CHEST);
            assertTrue(chat(fa).contains("You can't deposit this item!"));
            a.getInventory().setItemStack(1, ItemStack.of(Material.STONE_SWORD));
            punch(game, a, CHEST);
            assertTrue(chat(fa).contains("You need at least 2 items of this type to deposit!"));
            a.getInventory().setItemStack(2, ItemStack.of(Material.STONE_SWORD));
            punch(game, a, CHEST);
            assertEquals(1, stored(game, a, CHEST, Material.STONE_SWORD), "one of two");
            assertEquals(Material.STONE_SWORD, a.getInventory().getItemStack(2).material());

            b.getInventory().setItemStack(1, ItemStack.of(Material.IRON_INGOT, 3));
            b.setHeldItemSlot((byte) 1);
            punch(game, b, CHEST);
            assertTrue(chat(fb).stream().anyMatch(l -> l.startsWith("You cannot deposit into this chest as the ")),
                    chat(fb).toString());
            assertEquals(3, b.getInventory().getItemStack(1).amount());
        });
    }

    @Test
    void anUpgradeReachesTheChests() {
        round("ChestC", (game, fa, fb) -> {
            Player a = fa.player;
            for (int slot = 1; slot <= 3; slot++) a.getInventory().setItemStack(slot, ItemStack.of(Material.STONE_SWORD));
            a.setHeldItemSlot((byte) 1);
            punch(game, a, CHEST);
            a.setHeldItemSlot((byte) 2);
            punch(game, a, ENDER);
            game.state().set(Scope.team(Match.teamOf(game, a)), Upgrades.SHARPNESS, 1);
            assertEquals(1, sharpness(first(game, a, CHEST)), "the team chest's sword");
            assertEquals(1, sharpness(first(game, a, ENDER)), "and the ender chest's");
        });
    }

    @Test
    void aFinalDeathSpillsTheEnderChest() {
        round("ChestD", (game, fa, fb) -> {
            Player a = fa.player, b = fb.player;
            a.getInventory().setItemStack(1, ItemStack.of(Material.OBSIDIAN, 4));
            a.setHeldItemSlot((byte) 1);
            punch(game, a, ENDER);
            assertEquals(4, stored(game, a, ENDER, Material.OBSIDIAN));

            String team = Match.teamOf(game, a);
            BlockVec bed = game.arena().marker(BedWars.BED, team).boxes().getFirst().min();
            EventDispatcher.call(new PlayerBlockBreakEvent(b, game.instance(), game.shard().getBlock(bed), Block.AIR, bed,
                    BlockFace.TOP));
            assertFalse(Beds.stands(game, team));
            fb.sent.clear();
            game.module(Lives.class).fell(a);
            assertTrue(chat(fb).contains("Contents of ChestD's Ender Chest have been dropped into their fountain."),
                    chat(fb).toString());
            Point forge = game.arena().marker(BedWars.FORGE, team).point();
            BedWarsRoundTest.tickUntil(game, "the obsidian at the forge", () -> lying(game, forge, Material.OBSIDIAN) == 4);
            assertEquals(0, stored(game, a, ENDER, Material.OBSIDIAN));
        });
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
            game.shard().setBlock(CHEST, Block.CHEST.withProperty("facing", "east"));
            game.shard().setBlock(ENDER, Block.ENDER_CHEST.withProperty("facing", "east"));
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

    private static void open(Game game, Player p, BlockVec pos) {
        EventDispatcher.call(new PlayerBlockInteractEvent(p, PlayerHand.MAIN, game.instance(), game.shard().getBlock(pos),
                pos, new Vec(0.5, 0.5, 0.5), BlockFace.WEST));
    }

    private static void punch(Game game, Player p, BlockVec pos) {
        EventDispatcher.call(new PlayerStartDiggingEvent(p, game.instance(), game.shard().getBlock(pos), pos, BlockFace.WEST));
    }

    private static InventoryPreClickEvent shift(Player p, int slot) {
        InventoryPreClickEvent e = new InventoryPreClickEvent(p.getInventory(), p, new Click.LeftShift(slot));
        EventDispatcher.call(e);
        return e;
    }

    private static int stored(Game game, Player p, BlockVec pos, Material material) {
        ItemStack[] slots = Mechanics.containers.contents(game.shard(), Mechanics.containers.keyAt(game.shard(), pos, p));
        int n = 0;
        if (slots != null) for (ItemStack in : slots) if (in != null && in.material() == material) n += in.amount();
        return n;
    }

    private static ItemStack first(Game game, Player p, BlockVec pos) {
        ItemStack[] slots = Mechanics.containers.contents(game.shard(), Mechanics.containers.keyAt(game.shard(), pos, p));
        assertNotNull(slots);
        for (ItemStack in : slots) if (in != null) return in;
        throw new AssertionError("nothing stored at " + pos);
    }

    private static int sharpness(ItemStack item) {
        return item.get(DataComponents.ENCHANTMENTS, EnchantmentList.EMPTY).level(Enchantment.SHARPNESS);
    }

    private static int lying(Game game, Point at, Material material) {
        return game.instance().getEntities().stream()
                .filter(e -> e instanceof DroppedItemEntity item && item.getItemStack().material() == material
                        && e.getPosition().distance(at) <= 3)
                .mapToInt(e -> ((DroppedItemEntity) e).getItemStack().amount()).sum();
    }

    private static List<String> chat(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }
}
