package tennacle.games.bedwars;

import tennacle.games.beds.Beds;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;
import tennacle.games.beds.Lives;
import tennacle.games.watch.Lineup;
import tennacle.looks.Palette;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.ActionBarPacket;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The compass: its window by mode, a tracker once the enemy beds are gone, and quick messages to the team. */
class CompassTest extends HeadlessServerTest {

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
    void theCompassOpensItsWindow() {
        Game game = launch("bedwars-solo");
        FakePlayer a = seat(game, "CompassA"), b = seat(game, "CompassB");
        try {
            BedWarsRoundTest.live(game);
            Player p = a.player;
            assertEquals(Material.COMPASS, p.getInventory().getItemStack(Compass.SLOT).material(), "with the kit");
            p.getInventory().setItemStack(1, p.getInventory().getItemStack(Compass.SLOT));
            p.setHeldItemSlot((byte) 1);
            EventDispatcher.call(new PlayerUseItemEvent(p, PlayerHand.MAIN, p.getItemInMainHand(), 0));
            assertEquals("Tracker & Communication", title(p));
            Inventory window = Screens.inventory(p);
            assertEquals(Material.COMPASS, window.getItemStack(13).material(), "Solo: the Tracker Shop alone");
            assertTrue(window.getItemStack(11).isAir());
        } finally {
            end(game, a, b);
        }
    }

    @Test
    void aTrackerWaitsForTheBeds() {
        Game game = launch("bedwars-solo");
        FakePlayer fa = seat(game, "TrackA"), fb = seat(game, "TrackB");
        try {
            BedWarsRoundTest.live(game);
            Player a = fa.player, b = fb.player;
            fa.sent.clear();
            TrackerShop.open(game, a, true);
            assertEquals("Purchase Enemy Tracker", title(a));
            click(a, 10);
            assertNull(Screens.screen(a), "every click closes it");
            assertTrue(chat(fa).contains("Not all enemy beds are destroyed yet!"), chat(fa).toString());

            String theirs = Match.teamOf(game, b);
            BlockVec bed = game.arena().marker(BedWars.BED, theirs).boxes().getFirst().min();
            EventDispatcher.call(new PlayerBlockBreakEvent(a, game.instance(), game.shard().getBlock(bed), Block.AIR, bed,
                    BlockFace.TOP));
            a.getInventory().addItemStack(ItemStack.of(Material.EMERALD, 2));
            TrackerShop.open(game, a, true);
            click(a, 10);
            String name = Palette.titled(game.roster().team(theirs));
            assertTrue(chat(fa).contains("You purchased " + name + " Tracking"), chat(fa).toString());
            assertTrue(chat(fa).contains("You will lose ability to track this team when you die!"));

            b.teleport(new Pos(10.5, 65, 6.5)).join();
            long until = game.tick() + 10;
            BedWarsRoundTest.tickUntil(game, "the readout", () -> game.tick() >= until);
            assertEquals(b.getPosition(), Lineup.aimOf(a).get(), "the compass on them");
            List<String> bar = fa.sent(ActionBarPacket.class).stream()
                    .map(p -> PlainTextComponentSerializer.plainText().serialize(p.text())).toList();
            assertFalse(bar.isEmpty());
            assertTrue(bar.getLast().startsWith("Tracking: " + b.getUsername() + " - Distance: "), bar.getLast());

            game.module(Lives.class).fell(a);
            assertEquals("", game.state().get(Scope.slot(game.roster().slotOf(a).index()), Compass.TRACKING), "gone at a death");
        } finally {
            end(game, fa, fb);
        }
    }

    @Test
    void aQuickMessageReachesTheTeam() {
        Game game = launch("bedwars-doubles");
        FakePlayer fa = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "CommsA");
        FakePlayer fb = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "CommsB");
        FakePlayer fc = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "CommsC");
        try {
            for (FakePlayer f : List.of(fa, fb, fc)) f.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(fa.player, fb.player)));
            assertTrue(Matches.enter(game, List.of(fc.player)));
            await("all seated", () -> Game.of(fa.player) == game && Game.of(fb.player) == game && Game.of(fc.player) == game);
            BedWarsRoundTest.live(game);
            Player a = fa.player;
            String team = Match.teamOf(game, a);

            Compass.menu(game, a);
            Inventory menu = Screens.inventory(a);
            assertEquals(Material.EMERALD, menu.getItemStack(11).material(), "a team mode: Quick Communications");
            assertEquals(Material.COMPASS, menu.getItemStack(15).material());

            fa.sent.clear();
            fb.sent.clear();
            fc.sent.clear();
            QuickComms.open(game, a);
            click(a, 12); // I'm defending!
            assertTrue(chat(fb).contains("TEAM > CommsA: I'm defending"), chat(fb).toString());
            assertTrue(chat(fa).contains("Targeting your base on the compass now!"), chat(fa).toString());
            assertTrue(chat(fc).stream().noneMatch(l -> l.startsWith("TEAM > ")), "the other side hears nothing");
            assertEquals(Beds.base(game, team), Lineup.aimOf(a).get());

            QuickComms.open(game, a);
            click(a, 14); // I'm collecting resources!
            assertEquals("Select an option: ", title(a));
            click(a, 10);
            assertTrue(chat(fb).contains("TEAM > CommsA: I'm collecting DIAMOND"), chat(fb).toString());
            assertTrue(chat(fa).contains("Targeting Diamond on the compass now!"), chat(fa).toString());
        } finally {
            end(game, fa, fb, fc);
        }
    }

    private static Game launch(String mode) {
        Mode type = Matches.mode(mode);
        Game game = BedWarsRoundTest.byArrival(Matches.launch(type, Matches.platformFor(null), BedWarsRoundTest.fixture()));
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        return game;
    }

    private static FakePlayer seat(Game game, String name) {
        FakePlayer p = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), name);
        p.player.setInstance(game.instance(), ROOM).join();
        assertTrue(Matches.enter(game, List.of(p.player)));
        await(name + " seated", () -> Game.of(p.player) == game);
        return p;
    }

    private static void end(Game game, FakePlayer... players) {
        if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
        for (FakePlayer p : players) p.player.remove();
    }

    private static void click(Player p, int slot) {
        Inventory open = Screens.inventory(p);
        assertNotNull(open, "a screen is open");
        EventDispatcher.call(new InventoryPreClickEvent(open, p, new Click.Left(slot)));
    }

    private static String title(Player p) {
        Inventory open = Screens.inventory(p);
        return open == null ? "" : PlainTextComponentSerializer.plainText().serialize(open.getTitle());
    }

    private static List<String> chat(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }
}
