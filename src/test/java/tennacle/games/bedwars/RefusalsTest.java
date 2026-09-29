package tennacle.games.bedwars;

import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.state.Scope;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.network.packet.server.play.SoundEffectPacket;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every refusal in the captured words for its reason, and a refused placement sounding to nobody else. */
class RefusalsTest extends HeadlessServerTest {

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
    void refusalsSayWhy() {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = Matches.launch(solo, Matches.platformFor(null), BedWarsRoundTest.fixture());
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer fa = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "RefuseA");
        FakePlayer fb = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "RefuseB");
        try {
            fa.player.setInstance(game.instance(), ROOM).join();
            fb.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(fa.player)));
            assertTrue(Matches.enter(game, List.of(fb.player)));
            await("both seated", () -> Game.of(fa.player) == game && Game.of(fb.player) == game);
            BedWarsRoundTest.live(game);
            Player a = fa.player;
            assertEquals("2", Match.teamOf(game, fb.player), "the fixture's second base is theirs");

            // within 1.8 reach of each target: Polyp refuses a far placement before the rules see it
            a.teleport(new Pos(10.5, 66, 0.5)).join();
            place(fa, fb, game, new BlockVec(11, 66, 0), true);
            assertTrue(said(fa, "You can't place blocks here!"), chat(fa).toString());
            assertTrue(fb.sent(SoundEffectPacket.class).isEmpty(), "a refused placement sounds to nobody else");

            a.teleport(new Pos(0.5, 61, 0.5)).join();
            place(fa, fb, game, new BlockVec(0, 59, 0), true);
            assertTrue(said(fa, "You cannot build any further down!"), chat(fa).toString());

            a.teleport(new Pos(3.5, 65, 1.5)).join();
            place(fa, fb, game, new BlockVec(3, 65, 3), false);
            assertFalse(said(fa, "You can"), "nothing to say: " + chat(fa));
            assertFalse(fb.sent(SoundEffectPacket.class).isEmpty(), "a placement that lands is heard");

            a.teleport(new Pos(0.5, 65, 0.5)).join();
            brk(fa, game, new BlockVec(0, 64, 0));
            assertTrue(said(fa, "You can only break blocks placed by a player!"), chat(fa).toString());
            a.teleport(new Pos(10.5, 66, 0.5)).join();
            brk(fa, game, new BlockVec(11, 64, 0));
            assertTrue(said(fa, "You can't break blocks here!"), chat(fa).toString());
        } finally {
            tennacle.games.watch.Replays.discard(game); // no replay still writing into the temp dir as it goes
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            fa.player.remove();
            fb.player.remove();
        }
    }

    @Test
    void zonesAroundGeneratorsAndKeepers() {
        Mode solo = Matches.mode("bedwars-solo");
        Game game = Matches.launch(solo, Matches.platformFor(null), BedWarsRoundTest.fixture());
        game.state().set(Scope.GAME, Countdown.START_AT, 2);
        game.state().set(Scope.GAME, Countdown.FROM, 1);
        FakePlayer fa = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "ZoneA");
        FakePlayer fb = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "ZoneB");
        try {
            fa.player.setInstance(game.instance(), ROOM).join();
            fb.player.setInstance(game.instance(), ROOM).join();
            assertTrue(Matches.enter(game, List.of(fa.player)));
            assertTrue(Matches.enter(game, List.of(fb.player)));
            await("both seated", () -> Game.of(fa.player) == game && Game.of(fb.player) == game);
            BedWarsRoundTest.live(game);
            Player a = fa.player;
            // the fixture's diamond generator drops at 0, 65, -16: its cube reaches 3 out every way
            a.teleport(new Pos(5.5, 65, -13.5)).join();
            place(fa, fb, game, new BlockVec(3, 65, -16), true);
            assertTrue(said(fa, "You can't place blocks here!"), chat(fa).toString());
            place(fa, fb, game, new BlockVec(4, 65, -16), false);
            // red's shopkeeper stands at -13, 65, 4: a 3 x 3 column from 5 below its feet to 5 above
            a.teleport(new Pos(-9.5, 65, 6.5)).join();
            place(fa, fb, game, new BlockVec(-12, 65, 5), true);
            place(fa, fb, game, new BlockVec(-11, 65, 5), false);
            a.teleport(new Pos(-10.5, 69, 6.5)).join();
            place(fa, fb, game, new BlockVec(-13, 70, 4), true);
            place(fa, fb, game, new BlockVec(-13, 71, 4), false);
        } finally {
            tennacle.games.watch.Replays.discard(game);
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            fa.player.remove();
            fb.player.remove();
        }
    }

    private static void place(FakePlayer p, FakePlayer other, Game game, BlockVec at, boolean refused) {
        p.sent.clear();
        other.sent.clear();
        var event = new PlayerBlockPlaceEvent(p.player, game.instance(), Block.OAK_PLANKS, BlockFace.TOP, at, new Pos(0.5, 1, 0.5), PlayerHand.MAIN);
        EventDispatcher.call(event);
        assertEquals(refused, event.isCancelled(), at.toString());
        if (!refused) game.shard().setBlock(at, Block.OAK_PLANKS);
        settle();
    }

    private static void brk(FakePlayer p, Game game, BlockVec at) {
        p.sent.clear();
        var event = new PlayerBlockBreakEvent(p.player, game.instance(), game.shard().getBlock(at), Block.AIR, at, BlockFace.TOP);
        EventDispatcher.call(event);
        assertTrue(event.isCancelled(), at.toString());
        settle();
    }

    // the verdict and the sound land at the end of the tick
    private static void settle() {
        for (int i = 0; i < 2; i++) {
            MinecraftServer.getSchedulerManager().processTick();
            MinecraftServer.getSchedulerManager().processTickEnd();
        }
    }

    private static List<String> chat(FakePlayer p) {
        return p.sent(SystemChatPacket.class).stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message())).toList();
    }

    private static boolean said(FakePlayer p, String line) {
        return chat(p).stream().anyMatch(l -> l.contains(line));
    }
}
