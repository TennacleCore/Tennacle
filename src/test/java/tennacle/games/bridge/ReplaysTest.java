package tennacle.games.bridge;

import tennacle.games.watch.Remote;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.Material;
import tennacle.games.watch.Replays;
import tennacle.bridge.ReplayBridge;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.archipelago.shard.ShardTabList;
import io.github.term4.archipelago.shard.ShardView;
import io.github.term4.canvas.board.Boards;
import io.github.term4.canvas.screen.Screens;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.archipelago.replay.Recording;
import io.github.term4.archipelago.replay.Replay;
import io.github.term4.archipelago.storage.ShardFile;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.player.PlayerChatEvent;
import net.minestom.server.network.packet.server.play.ScoreboardObjectivePacket;
import net.minestom.server.network.packet.server.play.SetTitleSubTitlePacket;
import net.minestom.server.network.packet.server.play.TeamsPacket;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A game records itself, lands as a file when it ends, tells its players where to watch it, and keeps what it said. */
class ReplaysTest extends HeadlessServerTest {

    @TempDir
    static Path replays;

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        ReplayBridge.install(); // the recording sources the server wires at boot
        Replays.install(replays, new io.github.term4.archipelago.shard.ShardManager());
    }

    private static boolean said(FakePlayer p, String line) {
        return p.sent(SystemChatPacket.class).stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c.message()))
                .anyMatch(l -> l.contains(line));
    }

    @Test
    void theClockReads() {
        assertEquals("15:02", Replays.clock(15 * 60 * 20 + 2 * 20));
        assertEquals("0:00", Replays.clock(0));
    }

    /** A crash leaves a spool; a run before the mode folders left a loose file: the boot sweep files both. */
    @Test
    void aBootFilesWhatWasLeft() throws Exception {
        Shard stage = new Shard(instance);
        Path spool = replays.resolve("spool").resolve("leftover.spool");
        Recording cut = Replay.record(stage).spool(spool, 5, data -> data.put(Replays.MODE, "sumo").put(Replays.CODE, "LEFT"));
        for (int i = 0; i < 12; i++) { // the harness has no ticker
            net.minestom.server.MinecraftServer.getSchedulerManager().processTick();
            net.minestom.server.MinecraftServer.getSchedulerManager().processTickEnd();
        }
        cut.stop(); // the appends settle; no discard, as a crash leaves it
        Path loose = replays.resolve("loose.shard");
        Files.createDirectories(replays);
        Files.write(loose, Replay.record(stage).stop(data -> data.put(Replays.MODE, "boxing")));

        Replays.install(replays, new ShardManager());
        Path filed = replays.resolve("sumo").resolve("leftover.shard");
        assertTrue(Files.exists(filed), "the spool became a file under its mode");
        assertFalse(Files.exists(spool), "and is gone");
        assertFalse(Files.exists(spool.getParent()), "with the folder from before the mode folders");
        assertEquals("LEFT", ShardFile.userData(Files.readAllBytes(filed)).get(Replays.CODE), "with the header's data");
        assertEquals(filed, Replays.fileOf("leftover"));
        assertTrue(Files.exists(replays.resolve("boxing").resolve("loose.shard")), "the loose file moved under its mode");
        assertFalse(Files.exists(loose));
    }

    /** A game the countdown never outlived is nothing to watch: no file, and its spool goes with it. */
    @Test
    void aCountdownDropsItsReplay() {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "DropRed");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            await("seated", () -> Game.of(red.player) == game);
            assertTrue(Replays.recording(game));
            assertFalse(Match.live(game), "still in the countdown");
            game.end(Outcome.aborted());
            Path file = replays.resolve("bridge").resolve(game.id() + ".shard");
            Path spool = replays.resolve("bridge").resolve("spool").resolve(game.id() + ".spool");
            await("the spool goes", () -> !Files.exists(spool));
            assertFalse(Files.exists(file), "and nothing is kept");
            assertFalse(said(red, "This game has been recorded"), "nobody is told about a game that never played");
        } finally {
            red.player.remove();
        }
    }

    /** A game the rule says not to record keeps nothing, not even a spool. */
    @Test
    void aRuleTurnsRecordingOff() {
        Mode bridge = Matches.mode("bridge");
        tennacle.games.Options off = new tennacle.games.Options(null, null,
                tennacle.games.Ruleset.named("norec").rule(Replays.RECORD, false), List.of(), List.of());
        Game game = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture(), off);
        try {
            assertFalse(Replays.recording(game), "nothing is recording");
            assertFalse(Files.exists(replays.resolve("bridge").resolve("spool").resolve(game.id() + ".spool")),
                    "and no spool was opened");
        } finally {
            game.end(Outcome.aborted());
        }
    }

    @Test
    void aGameLandsAsAFileAndSaysSo() {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "RecRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "RecBlue");
        try {
            assertTrue(Replays.recording(game), "recording from the launch");
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            BridgeRoundTest.tickUntil(game, "GO", () -> Match.live(game));
            game.depart(red.player); // gone before the end: still told
            red.sent.clear();
            blue.sent.clear();
            game.decide(Outcome.draw(), 1);
            BridgeRoundTest.tickUntil(game, "the game ends", () -> game.status() != Game.Status.OPEN);
            Path file = replays.resolve("bridge").resolve(game.id() + ".shard");
            await("the file lands under its mode", () -> Files.exists(file));
            assertEquals(file, Replays.fileOf(game.id()));
            await("the players hear it", () -> said(red, "This game has been recorded") && said(blue, "This game has been recorded"));
            String json = blue.sent(SystemChatPacket.class).stream()
                    .map(c -> GsonComponentSerializer.gson().serialize(c.message()))
                    .filter(l -> l.contains("recorded")).findFirst().orElseThrow();
            assertTrue(json.contains("copy_to_clipboard") && json.contains("/replay watch " + game.id()),
                    "the click copies the watch command with the full id: " + json);
            assertTrue(json.contains("bridge-fixture"), "the hover names the map: " + json);
            await("the spool is gone", () -> !Files.exists(replays.resolve("bridge").resolve("spool").resolve(game.id() + ".spool")));
        } finally {
            red.player.remove();
            blue.player.remove();
        }
    }

    /** Two people who start the same replay apart each get a theater: separate shards, separate controls. */
    /** A party watches behind its leader: one theater, and the watchers are not invisible to each other in it. */
    @Test
    void aPartyWatchesTogetherAndSeesItself() {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "PartyRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "PartyBlue");
        FakePlayer lead = FakePlayer.connect(instance, new Pos(4.5, 65, 0.5), "WatchLead");
        FakePlayer mate = FakePlayer.connect(instance, new Pos(6.5, 65, 0.5), "WatchMate");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            BridgeRoundTest.tickUntil(game, "GO", () -> Match.live(game));
            game.decide(Outcome.draw(), 1);
            BridgeRoundTest.tickUntil(game, "the game ends", () -> game.status() != Game.Status.OPEN);
            await("the file lands", () -> Files.exists(replays.resolve("bridge").resolve(game.id() + ".shard")));

            MinecraftServer.getCommandManager().execute(lead.player, "party WatchMate");
            MinecraftServer.getCommandManager().execute(mate.player, "party join WatchLead");
            assertEquals(2, tennacle.games.matchmaking.Parties.of(lead.player).size(), "a party of two");

            Replays.watch(lead.player, game.id(), BridgeRoundTest.fixture());
            await("both admitted", () -> Replays.playbackOf(mate.player) != null);
            assertEquals(Replays.playbackOf(lead.player), Replays.playbackOf(mate.player), "one theater between them");
            assertTrue(ShardView.of(lead.player).seesEntity(mate.player), "the leader sees who came along");
            assertTrue(ShardView.of(mate.player).seesEntity(lead.player), "and is seen back");

            Replays.leave(lead.player);
            Matches.toLobby(lead.player);
            Replays.leave(mate.player);
            Matches.toLobby(mate.player);
        } finally {
            red.player.remove();
            blue.player.remove();
            lead.player.remove();
            mate.player.remove();
        }
    }

    @Test
    void twoWatchersEachGetATheater() {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "TwoRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "TwoBlue");
        FakePlayer one = FakePlayer.connect(instance, new Pos(4.5, 65, 0.5), "TwoWatchA");
        FakePlayer two = FakePlayer.connect(instance, new Pos(6.5, 65, 0.5), "TwoWatchB");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            BridgeRoundTest.tickUntil(game, "GO", () -> Match.live(game));
            game.decide(Outcome.draw(), 1);
            BridgeRoundTest.tickUntil(game, "the game ends", () -> game.status() != Game.Status.OPEN);
            await("the file lands", () -> Files.exists(replays.resolve("bridge").resolve(game.id() + ".shard")));

            Replays.watch(one.player, game.id(), BridgeRoundTest.fixture());
            Replays.watch(two.player, game.id(), BridgeRoundTest.fixture());
            Playback first = Replays.playbackOf(one.player);
            Playback second = Replays.playbackOf(two.player);
            assertNotNull(first);
            assertNotNull(second);
            assertNotEquals(first, second, "each watcher plays their own");
            assertNotEquals(first.shard(), second.shard(), "on a shard of their own");
            assertEquals(game.id(), Replays.watching(one.player));
            assertEquals(game.id(), Replays.watching(two.player));

            first.pause(true);
            assertFalse(second.paused(), "one remote never reaches the other");
            assertTrue(Replays.leave(one.player), "the first leaves");
            Matches.toLobby(one.player);
            assertFalse(first.active(), "and their theater closes");
            assertTrue(second.active(), "the other plays on");
            assertTrue(Replays.leave(two.player));
            Matches.toLobby(two.player);
            assertFalse(second.active());
        } finally {
            red.player.remove();
            blue.player.remove();
            one.player.remove();
            two.player.remove();
        }
    }

    @Test
    void theGamesChannelPlaysBack() throws Exception {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "ChanRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "ChanBlue");
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(4.5, 65, 0.5), "ChanViewer");
        FakePlayer mate = FakePlayer.connect(instance, new Pos(6.5, 65, 0.5), "ChanMate");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            BridgeRoundTest.tickUntil(game, "GO", () -> Match.live(game));
            EventDispatcher.call(new PlayerChatEvent(red.player, List.of(red.player, blue.player), "gg"));
            game.decide(Outcome.draw(), 1);
            BridgeRoundTest.tickUntil(game, "the game ends", () -> game.status() != Game.Status.OPEN);
            Path file = replays.resolve("bridge").resolve(game.id() + ".shard");
            await("the file lands", () -> Files.exists(file));
            MinecraftServer.getCommandManager().execute(viewer.player, "party ChanMate");
            MinecraftServer.getCommandManager().execute(mate.player, "party accept");
            viewer.sent.clear();
            Replays.watch(viewer.player, game.id(), BridgeRoundTest.fixture()); // the fixture is in no pool
            Playback playback = Replays.playbackOf(viewer.player);
            assertNotNull(playback, "the watch raised a theater");
            assertEquals(playback, Replays.playbackOf(mate.player), "the party watches behind its leader");
            assertTrue(playback.counts().getOrDefault("tennacle:line", 0) > 0, playback.counts().toString());
            assertTrue(playback.counts().getOrDefault("tennacle:title", 0) > 0, playback.counts().toString());
            assertTrue(playback.counts().getOrDefault("tennacle:rows", 0) > 0, "the board recorded: " + playback.counts());
            Shard theater = playback.shard();
            tickUntil(theater, "the channel plays back: " + playback.counts(),
                    () -> said(viewer, "ChanRed: gg") && said(viewer, "Draw.") && subtitled(viewer, "Cages open in"));
            assertFalse(said(viewer, "This game has been recorded"), "the recorded line is the players' alone");
            Boards.Rendered board = Boards.rendered(viewer.player);
            assertNotNull(board, "the game's board shows in the theater");
            List<String> rows = board.lines().stream().map(l -> PlainTextComponentSerializer.plainText().serialize(l)).toList();
            assertTrue(rows.stream().anyMatch(l -> l.contains("Time:")), "the recorded rows: " + rows);
            assertEquals("REPLAY", PlainTextComponentSerializer.plainText().serialize(board.title()), "the theater's title");
            String today = java.time.LocalDate.now().toString();
            int dated = rows.indexOf(today);
            assertTrue(dated >= 0 && dated + 4 <= rows.size(), "today's date on the board: " + rows);
            assertEquals(List.of(today, "Recorded:", "» " + today, "» " + game.code()), rows.subList(dated, dated + 4).stream().map(String::trim).toList(),
                    "today, then the recording's own date and code, a row each: " + rows);
            assertTrue(rows.get(dated + 2).startsWith(" ") && rows.get(dated + 3).startsWith(" "), "the recording's rows sit in: " + rows);
            assertTrue(viewer.sent(TeamsPacket.class).stream().anyMatch(t -> t.action() instanceof TeamsPacket.CreateTeamAction c
                    && c.entities().contains("ChanRed")), "the lineup colors the ghosts' names");
            assertTrue(viewer.sent(ScoreboardObjectivePacket.class).stream().anyMatch(o -> o.objectiveName().startsWith("rh-")),
                    "the health column is up");
            assertTrue(Boolean.TRUE.equals(viewer.player.getTag(ShardTabList.SPECTATOR)), "a watcher lists as a spectator");
            assertEquals(Material.LIME_DYE, viewer.player.getInventory().getItemStack(Remote.PLAY).material(), "the leader holds the remote");
            assertEquals(Material.PLAYER_HEAD, viewer.player.getInventory().getItemStack(Remote.FASTER).material());
            assertEquals(Material.COMPASS, viewer.player.getInventory().getItemStack(Remote.PLAYERS).material());
            assertEquals(Material.NETHER_STAR, viewer.player.getInventory().getItemStack(Remote.MENU).material());
            assertEquals(Material.PLAYER_HEAD, viewer.player.getInventory().getItemStack(Remote.DIRECTION).material());
            Remote remote = Remote.of(viewer.player);
            assertNotNull(remote);
            remote.players(viewer.player);
            var picker = Screens.inventory(viewer.player);
            assertNotNull(picker, "the compass opens the players");
            assertEquals(6 * 9, picker.getSize(), "the picker fills a double chest, paged like the others");
            assertEquals(Material.PLAYER_HEAD, picker.getItemStack(9).material(), "a head per player alive");
            assertEquals(Material.PLAYER_HEAD, picker.getItemStack(10).material());
            remote.menu(viewer.player);
            var menu = Screens.inventory(viewer.player);
            assertNotNull(menu, "the star opens the menu");
            assertEquals(Material.BOOK, menu.getItemStack(13).material(), "bookmarks in the middle");
            viewer.player.closeInventory();
            assertTrue(mate.player.getInventory().getItemStack(Remote.PLAY).isAir(), "a member watches bare-handed");
            assertTrue(viewer.player.getExp() > 0f, "the bar reads the timeline: " + viewer.player.getExp());
            var grab = new InventoryPreClickEvent(viewer.player.getInventory(), viewer.player, new Click.Left(Remote.PLAY));
            EventDispatcher.call(grab);
            assertTrue(grab.isCancelled(), "the remote is not for moving");
            playback.pause(true); // the theater ticks on its own clock: the dye follows on its next tick
            await("the dye follows the state", () -> viewer.player.getInventory().getItemStack(Remote.PLAY).material() == Material.GRAY_DYE);
            playback.pause(false);
            assertEquals(-1, viewer.player.getTag(ShardTabList.LIST_ORDER), "a modern watcher's own row sinks on its order");
            assertEquals("\u00a7oChanViewer", viewer.player.getTag(ShardTabList.SORT_NAME), "a 1.8 row apart from the ghost's");
            assertFalse(viewer.sent(TeamsPacket.class).stream().anyMatch(t -> t.action() instanceof TeamsPacket.CreateTeamAction c
                    && c.entities().contains("ChanViewer")), "a modern watcher's name joins no team: their ghost keeps its color");
            // backward: the beats' titles show again and the board walks back to an earlier reading
            Boards.Rendered late = Boards.rendered(viewer.player);
            viewer.sent.clear();
            playback.reverse(true);
            tickUntil(theater, "the cage beats cross backward", () -> subtitled(viewer, "Cages open in"));
            assertTrue(PlainTextComponentSerializer.plainText().serialize(viewer.player.getInventory().getItemStack(Remote.DIRECTION)
                    .get(net.minestom.server.component.DataComponents.CUSTOM_NAME)).contains("reverse"), "the head follows the direction");
            tickUntil(theater, "the board walks back", () -> !late.equals(Boards.rendered(viewer.player)));
            playback.reverse(false);
            tickUntil(theater, "the replay runs out and holds its last frame", () -> playback.paused() && playback.tick() >= playback.length());
            assertTrue(playback.active(), "the theater stays up");
            assertEquals(game.id(), Replays.watching(viewer.player), "and so does the watcher");
            tickUntil(theater, "told so", () -> said(viewer, "The replay is over."));
            assertTrue(Replays.leave(viewer.player), "the leader leaves");
            Matches.toLobby(viewer.player);
            assertNull(Replays.watching(viewer.player));
            assertNull(Replays.watching(mate.player), "the party went home too");
            assertFalse(playback.active(), "the last one out closed the theater");
            await("back in the lobby", () -> viewer.player.getInstance() == instance);
            assertNull(viewer.player.getTag(ShardTabList.SPECTATOR));
            assertTrue(viewer.player.getInventory().getItemStack(Remote.PLAY).isAir(), "the remote stays in the theater");
        } finally {
            Replays.leave(viewer.player);
            MinecraftServer.getCommandManager().execute(viewer.player, "party disband");
            red.player.remove();
            blue.player.remove();
            viewer.player.remove();
            mate.player.remove();
        }
    }

    // a plain shard's clock is the server scheduler
    private static void tickUntil(Shard stage, String what, Supplier<Boolean> done) {
        long deadline = System.currentTimeMillis() + 8000;
        while (!done.get() && System.currentTimeMillis() < deadline) {
            if (stage.instance().isRegistered()) stage.instance().tick(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
            MinecraftServer.getSchedulerManager().processTick();
            MinecraftServer.getSchedulerManager().processTickEnd();
            try {
                Thread.sleep(2);
            } catch (InterruptedException ignored) {
            }
        }
        assertTrue(done.get(), what);
    }

    private static boolean subtitled(FakePlayer p, String text) {
        return p.sent(SetTitleSubTitlePacket.class).stream()
                .map(t -> PlainTextComponentSerializer.plainText().serialize(t.subtitle()))
                .anyMatch(l -> l.contains(text));
    }
}
