package tennacle.games.bridge;

import tennacle.games.watch.Remote;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.Material;
import tennacle.games.watch.Theaters;
import tennacle.games.watch.Tapes;
import tennacle.games.watch.Replays;
import tennacle.games.watch.Spectators;
import tennacle.glue.ReplayBridge;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.TestWiring;

import io.github.term4.barnacle.Arena;
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
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.network.packet.server.play.ScoreboardObjectivePacket;
import net.minestom.server.network.packet.server.play.SetTitleSubTitlePacket;
import net.minestom.server.network.packet.server.play.TeamsPacket;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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


    /** {@link Theaters#watch} reads, decodes and loads off the tick, then admits on it: pump until it is up. */
    private static void watch(FakePlayer viewer, String id, Arena arena) {
        Object before = Theaters.playbackOf(viewer.player); // a hop replaces one theater with another
        Theaters.watch(viewer.player, id, arena);
        long deadline = System.currentTimeMillis() + 4000;
        while (Theaters.playbackOf(viewer.player) == before && System.currentTimeMillis() < deadline) {
            MinecraftServer.getSchedulerManager().processTick();
            MinecraftServer.getSchedulerManager().processTickEnd();
        }
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
        assertTrue(java.util.Arrays.equals(Files.readAllBytes(filed), Tapes.store().bytes("leftover").join()), "and the store finds it");
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
    void aGameLandsAsAFileAndSaysSo() throws Exception {
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
            assertTrue(java.util.Arrays.equals(Files.readAllBytes(file), Tapes.store().bytes(game.id()).join()));
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
            assertEquals(2, tennacle.social.party.Parties.of(lead.player).size(), "a party of two");

            watch(lead, game.id(), BridgeRoundTest.fixture());
            await("both admitted", () -> Theaters.playbackOf(mate.player) != null);
            assertEquals(Theaters.playbackOf(lead.player), Theaters.playbackOf(mate.player), "one theater between them");
            assertTrue(ShardView.of(lead.player).seesEntity(mate.player), "the leader sees who came along");
            assertTrue(ShardView.of(mate.player).seesEntity(lead.player), "and is seen back");

            Theaters.leave(lead.player);
            Matches.toLobby(lead.player);
            Theaters.leave(mate.player);
            Matches.toLobby(mate.player);
        } finally {
            red.player.remove();
            blue.player.remove();
            lead.player.remove();
            mate.player.remove();
        }
    }

    @Test
    void aHopJoinsTheHostsTheater() {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "HopRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "HopBlue");
        FakePlayer host = FakePlayer.connect(instance, new Pos(4.5, 65, 0.5), "HopHost");
        FakePlayer guest = FakePlayer.connect(instance, new Pos(6.5, 65, 0.5), "HopGuest");
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

            watch(host, game.id(), BridgeRoundTest.fixture());
            Playback show = Theaters.playbackOf(host.player);
            assertNotNull(show, "the host opened a theater");
            tickUntil(show.shard(), "the host is dressed", () -> held(host.player) > 0);

            tennacle.audit.Audit.go(guest.player, host.player);
            assertEquals(show, Theaters.playbackOf(guest.player), "the host's theater, not one of the guest's own");
            tickUntil(show.shard(), "the guest is dressed",
                    () -> Integer.valueOf(-1).equals(guest.player.getTag(ShardTabList.LIST_ORDER)));
            assertEquals(1, held(guest.player), "and a guest carries the compass alone");
            assertEquals(Material.COMPASS, guest.player.getInventory().getItemStack(Remote.PLAYERS).material());
            assertTrue(guest.player.getInventory().getItemStack(Remote.PLAY).isAir(), "none of the remote");

            Theaters.leave(guest.player);
            Matches.toLobby(guest.player);
            Theaters.leave(host.player);
            Matches.toLobby(host.player);
        } finally {
            red.player.remove();
            blue.player.remove();
            host.player.remove();
            guest.player.remove();
        }
    }

    private static int held(net.minestom.server.entity.Player p) {
        int n = 0;
        for (int slot = 0; slot < p.getInventory().getSize(); slot++) {
            if (!p.getInventory().getItemStack(slot).isAir()) n++;
        }
        return n;
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

            watch(one, game.id(), BridgeRoundTest.fixture());
            watch(two, game.id(), BridgeRoundTest.fixture());
            Playback first = Theaters.playbackOf(one.player);
            Playback second = Theaters.playbackOf(two.player);
            assertNotNull(first);
            assertNotNull(second);
            assertNotEquals(first, second, "each watcher plays their own");
            assertNotEquals(first.shard(), second.shard(), "on a shard of their own");
            assertEquals(game.id(), Theaters.watching(one.player));
            assertEquals(game.id(), Theaters.watching(two.player));

            first.pause(true);
            assertFalse(second.paused(), "one remote never reaches the other");
            assertTrue(Theaters.leave(one.player), "the first leaves");
            Matches.toLobby(one.player);
            assertFalse(first.active(), "and their theater closes");
            assertTrue(second.active(), "the other plays on");
            assertTrue(Theaters.leave(two.player));
            Matches.toLobby(two.player);
            assertFalse(second.active());
        } finally {
            red.player.remove();
            blue.player.remove();
            one.player.remove();
            two.player.remove();
        }
    }

    /** The last word on a side's arm is that side's colour - the teardown's clear used to have it instead. */
    @Test
    void aSectionKeepsItsSideToTheEnd() throws Exception {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "PaintRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "PaintBlue");
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

            watch(red, game.id(), BridgeRoundTest.fixture());
            Playback playback = Theaters.playbackOf(red.player);
            assertNotNull(playback);
            Map<Integer, String> last = new java.util.LinkedHashMap<>(); // the fold keeps the latest per cell
            for (var op : playback.ops(io.github.term4.archipelago.replay.core.Channels.BLOCKS)) {
                var b = op.op();
                if (b.y() == 64 && b.z() == 0 && b.x() != 0 && Math.abs(b.x()) <= 8) last.put(b.x(), b.block());
            }
            assertEquals(16, last.size(), "both arms were recorded");
            last.forEach((x, block) -> assertEquals(
                    x < 0 ? "minecraft:blue_terracotta" : "minecraft:red_terracotta", block,
                    "x=" + x + " ends on " + block));
        } finally {
            Theaters.leave(red.player);
            Matches.toLobby(red.player);
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    /** No game watches a theater's void line, so the replay does: off the map is back where they came in. */
    @Test
    void aWatcherOffTheMapComesBack() throws Exception {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "FellRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "FellBlue");
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

            watch(red, game.id(), BridgeRoundTest.fixture());
            Playback playback = Theaters.playbackOf(red.player);
            assertNotNull(playback);
            tickUntil(playback.shard(), "the theater dressed them",
                    () -> Integer.valueOf(-1).equals(red.player.getTag(ShardTabList.LIST_ORDER)));
            Pos arrival = red.player.getPosition();

            red.player.teleport(new Pos(0.5, 40, 0.5)).join(); // under the fixture's void plane
            EventDispatcher.call(new PlayerMoveEvent(red.player, red.player.getPosition(), true));
            tickUntil(playback.shard(), "back where they came in",
                    () -> red.player.getPosition().samePoint(arrival));
        } finally {
            Theaters.leave(red.player);
            Matches.toLobby(red.player);
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    /** A viewer who turns up in another world has left the theater, whatever took them there. */
    @Test
    void aViewerTakenAwayLeavesTheTheater() throws Exception {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "StrayRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "StrayBlue");
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

            watch(red, game.id(), BridgeRoundTest.fixture());
            Playback playback = Theaters.playbackOf(red.player);
            assertNotNull(playback);
            tickUntil(playback.shard(), "the theater dressed them",
                    () -> Integer.valueOf(-1).equals(red.player.getTag(ShardTabList.LIST_ORDER)));

            red.player.setInstance(instance, new Pos(0.5, 65, 0.5)).join(); // a bare move, no leave call
            await("the theater let them go", () -> Theaters.watching(red.player) == null);
        } finally {
            Theaters.leave(red.player);
            Matches.toLobby(red.player);
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    /** Every hand-off between the two ways of watching just works: no leaving in between. */
    @Test
    void watchingHandsOffToWatching() throws Exception {
        Mode bridge = Matches.mode("bridge");
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "HandRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "HandBlue");
        FakePlayer watcher = FakePlayer.connect(instance, new Pos(4.5, 65, 0.5), "HandWatch");
        String first = recorded(bridge, red, blue);
        String second = recorded(bridge, red, blue);
        Game live = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        try {
            red.player.setInstance(live.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(live.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(live.join(red.player));
            assertTrue(live.join(blue.player));
            await("seated for the live one", () -> Game.of(red.player) == live);

            watch(watcher, first, BridgeRoundTest.fixture());
            assertEquals(first, Theaters.watching(watcher.player), "into the first replay");

            // a second watch from inside the first: straight across, no /l
            watch(watcher, second, BridgeRoundTest.fixture());
            assertEquals(second, Theaters.watching(watcher.player), "and straight into the second");

            // and out of a replay into a live game's crowd
            Spectators.enter(live, watcher.player, red.player);
            assertNull(Theaters.watching(watcher.player), "the theater let go");
            assertSame(live, Matches.grotto().spectating(watcher.player), "watching the live game now");

            // and back the other way
            watch(watcher, first, BridgeRoundTest.fixture());
            assertEquals(first, Theaters.watching(watcher.player), "back into a replay");
            assertNull(Matches.grotto().spectating(watcher.player), "and no longer in the crowd");
        } finally {
            Theaters.leave(watcher.player);
            Spectators.exit(watcher.player);
            Matches.toLobby(watcher.player);
            if (live.status() == Game.Status.OPEN) live.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
            watcher.player.remove();
        }
    }

    /** A party watching one replay moves to the next together - nobody is dropped in the lobby on the way. */
    @Test
    void aPartyHopsBetweenReplaysTogether() throws Exception {
        Mode bridge = Matches.mode("bridge");
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "HopRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "HopBlue");
        FakePlayer lead = FakePlayer.connect(instance, new Pos(4.5, 65, 0.5), "HopLead");
        FakePlayer mate = FakePlayer.connect(instance, new Pos(6.5, 65, 0.5), "HopMate");
        String first = recorded(bridge, red, blue);
        String second = recorded(bridge, red, blue);
        try {
            MinecraftServer.getCommandManager().execute(lead.player, "party HopMate");
            MinecraftServer.getCommandManager().execute(mate.player, "party join HopLead");
            assertEquals(2, tennacle.social.party.Parties.of(lead.player).size(), "a party of two");

            watch(lead, first, BridgeRoundTest.fixture());
            Playback one = Theaters.playbackOf(lead.player);
            assertNotNull(one);
            tickUntil(one.shard(), "both standing in the first", () -> standing(one, lead, mate));

            watch(lead, second, BridgeRoundTest.fixture());
            Playback two = Theaters.playbackOf(lead.player);
            assertNotNull(two);
            assertNotSame(one, two, "a second theater, not the first again");
            tickUntil(two.shard(), "both standing in the second", () -> standing(two, lead, mate));
            assertEquals(second, Theaters.watching(mate.player), "the party crossed with its leader");
        } finally {
            Theaters.leave(lead.player);
            Theaters.leave(mate.player);
            Matches.toLobby(lead.player);
            Matches.toLobby(mate.player);
            red.player.remove();
            blue.player.remove();
            lead.player.remove();
            mate.player.remove();
        }
    }

    /** A refused hand-off costs nothing: the view they had stands, and watching what they watch is a no-op. */
    @Test
    void arefusedHandOffKeepsTheViewTheyHad() throws Exception {
        Mode bridge = Matches.mode("bridge");
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "KeepRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "KeepBlue");
        FakePlayer watcher = FakePlayer.connect(instance, new Pos(4.5, 65, 0.5), "KeepWatch");
        String replay = recorded(bridge, red, blue);
        Game live = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        try {
            red.player.setInstance(live.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(live.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(live.join(red.player));
            assertTrue(live.join(blue.player));
            await("seated", () -> Game.of(red.player) == live);
            live.state().set(io.github.term4.grotto.state.Scope.GAME,
                    io.github.term4.grotto.modules.view.Spectating.ALLOWED, false);

            watch(watcher, replay, BridgeRoundTest.fixture());
            Playback stage = Theaters.playbackOf(watcher.player);
            assertNotNull(stage);
            tickUntil(stage.shard(), "in the theater", () -> standing(stage, watcher));

            // a game that takes no crowd says so - and does not cost them the replay they were in
            MinecraftServer.getCommandManager().execute(watcher.player, "spectate KeepRed");
            assertNull(Matches.grotto().spectating(watcher.player), "no crowd to join");
            assertEquals(replay, Theaters.watching(watcher.player), "and the replay they had stands");

            // and asking for the replay they are already in leaves it exactly as it was
            watch(watcher, replay, BridgeRoundTest.fixture());
            assertSame(stage, Theaters.playbackOf(watcher.player), "the same theater, not a second of it");
        } finally {
            Theaters.leave(watcher.player);
            Matches.toLobby(watcher.player);
            if (live.status() == Game.Status.OPEN) live.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
            watcher.player.remove();
        }
    }

    private static boolean standing(Playback stage, FakePlayer... watchers) {
        for (FakePlayer w : watchers) {
            if (w.player.getInstance() != stage.shard().instance() || Theaters.playbackOf(w.player) != stage) return false;
        }
        return true;
    }

    // one whole game, played and ended, as a replay id on disk
    private static String recorded(Mode bridge, FakePlayer red, FakePlayer blue) throws Exception {
        Game game = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
        blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
        assertTrue(game.join(red.player));
        assertTrue(game.join(blue.player));
        await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
        BridgeRoundTest.tickUntil(game, "GO", () -> Match.live(game));
        game.decide(Outcome.draw(), 1);
        BridgeRoundTest.tickUntil(game, "the game ends", () -> game.status() != Game.Status.OPEN);
        await("the file lands", () -> Files.exists(replays.resolve("bridge").resolve(game.id() + ".shard")));
        return game.id();
    }

    /** A player watching a game they played in shares a name with their own ghost; a team is keyed by name. */
    @Test
    void aWatcherWhoPlayedStandsPlain() throws Exception {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor(null), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "PlayedRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "PlayedBlue");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            BridgeRoundTest.tickUntil(game, "GO", () -> Match.live(game));
            game.decide(Outcome.draw(), 1);
            BridgeRoundTest.tickUntil(game, "the game ends", () -> game.status() != Game.Status.OPEN);
            Path file = replays.resolve("bridge").resolve(game.id() + ".shard");
            await("the file lands", () -> Files.exists(file));

            red.sent.clear();
            watch(red, game.id(), BridgeRoundTest.fixture()); // one of the two who played it
            Playback playback = Theaters.playbackOf(red.player);
            assertNotNull(playback);
            tickUntil(playback.shard(), "the theater dressed them",
                    () -> Integer.valueOf(-1).equals(red.player.getTag(ShardTabList.LIST_ORDER)));

            for (var packet : red.sent(TeamsPacket.class)) {
                if (!(packet.action() instanceof TeamsPacket.CreateTeamAction made)) continue;
                assertFalse(made.entities().contains("PlayedRed"),
                        "their own name never joins a recorded side: it would paint their living body");
            }
            // so the color rides the ghost's own entry instead, which a name-keyed team could never reach
            tickUntil(playback.shard(), "their ghost rises", () -> playback.ghost("PlayedRed") != null);
            var ghost = red.sent(net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket.class).stream()
                    .flatMap(info -> info.entries().stream())
                    .filter(e -> e.username().equals("PlayedRed") && !e.uuid().equals(red.player.getUuid()))
                    .reduce((first, last) -> last).orElse(null);
            assertNotNull(ghost, "their ghost's profile reached them");
            assertNotNull(ghost.displayName(), "and it reads in the side's color");
        } finally {
            Theaters.leave(red.player);
            Matches.toLobby(red.player);
            if (game.status() == Game.Status.OPEN) game.end(Outcome.aborted());
            red.player.remove();
            blue.player.remove();
        }
    }

    /** The board and the co-watchers a party sees, on a look whose board holds rows only a seat gets. */
    @Test
    void aMinemenTheaterShowsItsBoardAndItsParty() throws Exception {
        Mode bridge = Matches.mode("bridge");
        Game game = Matches.launch(bridge, Matches.platformFor("minemen"), BridgeRoundTest.fixture());
        FakePlayer red = FakePlayer.connect(instance, new Pos(0.5, 65, 0.5), "MmcRed");
        FakePlayer blue = FakePlayer.connect(instance, new Pos(2.5, 65, 0.5), "MmcBlue");
        FakePlayer viewer = FakePlayer.connect(instance, new Pos(4.5, 65, 0.5), "MmcViewer");
        FakePlayer mate = FakePlayer.connect(instance, new Pos(6.5, 65, 0.5), "MmcMate");
        try {
            red.player.setInstance(game.instance(), new Pos(-4.5, 65, 0.5)).join();
            blue.player.setInstance(game.instance(), new Pos(5.5, 65, 0.5)).join();
            assertTrue(game.join(red.player));
            assertTrue(game.join(blue.player));
            await("both seated", () -> Game.of(red.player) == game && Game.of(blue.player) == game);
            BridgeRoundTest.tickUntil(game, "GO", () -> Match.live(game));
            Boards.Rendered live = Boards.rendered(red.player);
            assertNotNull(live, "the seat reads a board while they play");
            assertTrue(io.github.term4.polyp.world.WorldPolicy.canAffect(red.player, blue.player),
                    "the two in the fight reach each other");
            game.decide(Outcome.draw(), 1);
            BridgeRoundTest.tickUntil(game, "the game ends", () -> game.status() != Game.Status.OPEN);
            Path file = replays.resolve("bridge").resolve(game.id() + ".shard");
            await("the file lands", () -> Files.exists(file));

            MinecraftServer.getCommandManager().execute(viewer.player, "party MmcMate");
            MinecraftServer.getCommandManager().execute(mate.player, "party accept");
            viewer.sent.clear();
            mate.sent.clear();
            watch(viewer, game.id(), BridgeRoundTest.fixture());
            Playback playback = Theaters.playbackOf(viewer.player);
            assertNotNull(playback, "the watch raised a theater");
            assertTrue(playback.counts().getOrDefault("tennacle:rows", 0) > 0,
                    "the board recorded: " + playback.counts());
            Shard theater = playback.shard();
            tickUntil(theater, "the recorded board shows", () -> Boards.rendered(viewer.player) != null);
            List<String> rows = Boards.rendered(viewer.player).lines().stream()
                    .map(l -> PlainTextComponentSerializer.plainText().serialize(l)).toList();
            assertTrue(rows.stream().anyMatch(l -> l.contains("Time Left")), "the recorded rows: " + rows);

            tickUntil(theater, "the party sees itself", () -> mate.player.getViewers().contains(viewer.player));
            assertTrue(viewer.player.getViewers().contains(mate.player), "and is seen back");
            assertAddBeforeSpawn(viewer, mate.player);
            assertAddBeforeSpawn(mate, viewer.player);
            assertFalse(io.github.term4.polyp.world.WorldPolicy.canAffect(viewer.player, mate.player),
                    "seen, not swung at: a watcher is in nobody's fight");
            assertFalse(io.github.term4.polyp.world.WorldPolicy.canAffect(mate.player, viewer.player));
            assertEquals(Math.round(mate.player.getHealth()), tabScore(viewer, mate.player),
                    "a watcher's row reads their own bar, not the zero an unscored one invents");
        } finally {
            Theaters.leave(viewer.player);
            Matches.toLobby(viewer.player);
            Theaters.leave(mate.player);
            Matches.toLobby(mate.player);
            red.player.remove();
            blue.player.remove();
            viewer.player.remove();
            mate.player.remove();
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
            watch(viewer, game.id(), BridgeRoundTest.fixture()); // the fixture is in no pool
            Playback playback = Theaters.playbackOf(viewer.player);
            assertNotNull(playback, "the watch raised a theater");
            assertEquals(playback, Theaters.playbackOf(mate.player), "the party watches behind its leader");
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
            assertNull(viewer.player.getTag(ShardTabList.SPECTATOR),
                    "never flagged a spectator: the flag draws a co-watcher as a floating head");
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
            assertTrue(mate.player.getInventory().getItemStack(Remote.PLAY).isAir(), "a member holds no remote");
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
            assertEquals(game.id(), Theaters.watching(viewer.player), "and so does the watcher");
            tickUntil(theater, "told so", () -> said(viewer, "The replay is over."));
            assertTrue(Theaters.leave(viewer.player), "the leader leaves");
            Matches.toLobby(viewer.player);
            assertNull(Theaters.watching(viewer.player));
            assertNull(Theaters.watching(mate.player), "the party went home too");
            assertFalse(playback.active(), "the last one out closed the theater");
            await("back in the lobby", () -> viewer.player.getInstance() == instance);
            assertNull(viewer.player.getTag(ShardTabList.SPECTATOR));
            assertTrue(viewer.player.getInventory().getItemStack(Remote.PLAY).isAir(), "the remote stays in the theater");
        } finally {
            Theaters.leave(viewer.player);
            MinecraftServer.getCommandManager().execute(viewer.player, "party disband");
            red.player.remove();
            blue.player.remove();
            viewer.player.remove();
            mate.player.remove();
        }
    }

    // a plain shard's clock is the server scheduler
    /** What {@code viewer}'s client was last told {@code subject} scores in the theater's health objective. */
    private static int tabScore(FakePlayer viewer, net.minestom.server.entity.Player subject) {
        // the row this viewer holds: a modern client was never sent the sort name
        String listed = io.github.term4.archipelago.shard.ShardTabList.listName(subject, viewer.player);
        return viewer.sent(net.minestom.server.network.packet.server.play.UpdateScorePacket.class).stream()
                .filter(p -> p.entityName().equals(listed))
                .reduce((a, b) -> b).orElseThrow(() -> new AssertionError("no score for " + listed)).score();
    }

    /** A client drops the spawn of a player it holds no tab entry for: the theater's row goes out first. */
    private static void assertAddBeforeSpawn(FakePlayer viewer, net.minestom.server.entity.Player subject) {
        var list = List.copyOf(viewer.sent);
        int add = -1, spawn = -1;
        for (int i = 0; i < list.size(); i++) {
            var pk = net.minestom.server.network.packet.server.SendablePacket
                    .extractServerPacket(net.minestom.server.network.ConnectionState.PLAY, list.get(i));
            if (add < 0 && pk instanceof net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket u
                    && u.entries().stream().anyMatch(en -> en.uuid().equals(subject.getUuid()))) add = i;
            if (spawn < 0 && pk instanceof net.minestom.server.network.packet.server.play.SpawnEntityPacket se
                    && se.entityId() == subject.getEntityId()) spawn = i;
        }
        assertTrue(spawn >= 0, "the theater spawned " + subject.getUsername());
        assertTrue(add >= 0 && add < spawn, "the row precedes the body (add@" + add + " spawn@" + spawn + ")");
    }

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
