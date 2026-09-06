package tennacle.games.bridge;

import tennacle.audit.Audit;
import tennacle.audit.AuditViews;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The auditor over a bridge game (the fixture lives here): a game's shard answers to its name and its game, its players read as heads that follow the roster. */
class AuditTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
    }

    private static String plain(List<Object> parts) {
        StringBuilder out = new StringBuilder();
        for (Object part : parts) {
            out.append(part instanceof Component c ? PlainTextComponentSerializer.plainText().serialize(c) : String.valueOf(part));
        }
        return out.toString();
    }

    @Test
    void handlesNameTheGame() {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        try {
            assertSame(game.shard(), Audit.shard(Audit.gameName(game)), "a game's type#code is its shard");
            assertSame(game.shard(), Audit.shard(Audit.handle(game.shard())), "and the handle rounds back");
            assertEquals("game " + Audit.gameName(game), Audit.owner(game.shard()));
            assertNull(Audit.shard("nothing#here"));
            assertTrue(Audit.alive(game.shard()));
        } finally {
            game.end(Outcome.aborted());
        }
    }

    @Test
    void headsFollowTheRoster() {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "AuditRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "AuditBlue");
        FakePlayer admin = FakePlayer.connect(instance, new Pos(4.5, 65, 0.5), "AuditAdmin");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            await("seated", () -> Game.of(red.player) == game);
            assertTrue(plain(Audit.where(red.player)).contains(Audit.gameName(game)), "where reads the game: " + plain(Audit.where(red.player)));
            assertTrue(plain(Audit.where(admin.player)).contains("lobby"), "the admin stands in the lobby");

            AuditViews.who(admin.player, game.shard());
            Inventory heads = Screens.inventory(admin.player);
            assertNotNull(heads, "the players open as a screen");
            assertEquals(Material.PLAYER_HEAD, heads.getItemStack(9).material(), "a head for the one seated");
            assertTrue(heads.getItemStack(10).isAir());

            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(blue.player) == game);
            for (int i = 0; i < 30 && heads.getItemStack(10).isAir(); i++) {
                MinecraftServer.getSchedulerManager().processTick();
                MinecraftServer.getSchedulerManager().processTickEnd();
            }
            assertEquals(Material.PLAYER_HEAD, heads.getItemStack(10).material(), "the second seat shows up on its own");
            assertSame(heads, Screens.inventory(admin.player), "in the same window");

            AuditViews.tree(admin.player, game.shard());
            Inventory tree = Screens.inventory(admin.player);
            assertNotNull(tree);
            if (game.shard().children().isEmpty()) { // nothing to expand: the players open instead
                assertEquals(Material.STICK, tree.getItemStack(AuditViews.NODE).material(), "a lone shard is a stick");
                assertEquals(Material.PLAYER_HEAD, tree.getItemStack(9).material());
            } else {
                assertEquals(Material.OAK_SAPLING, tree.getItemStack(AuditViews.NODE).material(), "a tree is a sapling");
                assertEquals(Material.PLAYER_HEAD, tree.getItemStack(AuditViews.ACROSS).material(), "the way across to the players");
            }
            for (Shard child : game.shard().children()) {
                assertEquals(child, Audit.shard(Audit.handle(child)));
            }
            admin.player.closeInventory();
        } finally {
            game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
            admin.player.remove();
        }
    }
}
