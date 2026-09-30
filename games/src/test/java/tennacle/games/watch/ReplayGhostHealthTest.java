package tennacle.games.watch;

import io.github.term4.archipelago.replay.Ghost;
import io.github.term4.archipelago.replay.Playback;
import io.github.term4.archipelago.replay.Recording;
import io.github.term4.archipelago.replay.Replay;
import io.github.term4.archipelago.Viewers;
import io.github.term4.archipelago.replay.core.CoreChannels;
import io.github.term4.archipelago.replay.core.StateChannels;
import io.github.term4.archipelago.shard.Shard;
import io.github.term4.archipelago.shard.ShardTabList;
import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.modules.view.HealthDisplay;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.TeamsPacket;
import net.minestom.server.network.packet.server.play.UpdateScorePacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tennacle.games.TestWiring;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reported from replays: every ghost's health read 0 in tab and over the head while a watcher's read 20. A 1.8
 * client names a ghost's row by its plated profile, so a score under the bare username lands on no row - and an
 * unscored row still draws a number there, which is zero.
 */
class ReplayGhostHealthTest extends HeadlessServerTest {

    @BeforeAll
    static void wire() {
        TestWiring.ensure(instance);
        CoreChannels.register();
        StateChannels.register();
        // the harness wires no era lever, and the replay wire tests share this convention
        Viewers.protocol(p -> p.getUsername().contains("Legacy") ? 47
                : p.getUsername().contains("Nine") ? 107 : MinecraftServer.PROTOCOL_VERSION);
    }

    private static void tick() {
        MinecraftServer.getSchedulerManager().processTick();
        MinecraftServer.getSchedulerManager().processTickEnd();
    }

    // one actor at 17 health, moved once so the ghost goes up on the playback's first move op
    private static byte[] recorded(String name, Pos at) {
        Shard stage = new Shard(instance);
        FakePlayer actor = FakePlayer.connect(instance, at, name);
        byte[] file;
        Recording rec = null;
        try {
            stage.join(actor.player);
            actor.player.setHealth(17f);
            rec = Replay.record(stage);
            tick();
            actor.player.teleport(at.add(2, 0, 0)).join();
            tick();
        } finally {
            file = rec != null ? rec.stop() : new byte[0];
            actor.player.remove();
        }
        return file;
    }

    @Test
    void aGhostScoresOnTheRowTheClientHolds() {
        byte[] file = recorded("GhostHpActor", new Pos(700.5, 65, 700.5));
        Shard theater = new Shard(instance);
        Playback playback = Replay.play(theater, file);
        Dressing dressing = new Dressing(theater, playback, "ghosthp1", "", LocalDate.now(), null, "tennacle",
                List.of("GhostHpActor|red|" + NamedTextColor.RED.value()), List.of(HealthDisplay.TAB));
        playback.ghostNames(dressing::listName);
        FakePlayer watcher = FakePlayer.connect(instance, new Pos(704.5, 65, 700.5), "GhostHpLegacy");
        try {
            theater.join(watcher.player);
            dressing.enter(watcher.player, true);
            watcher.sent.clear();
            for (int i = 0; i < 4; i++) tick();

            Ghost ghost = playback.ghost("GhostHpActor");
            assertNotNull(ghost, "the move op raised the ghost");
            String row = ghost.listedName(watcher.player);
            assertEquals("§c" + "GhostHpActor", row, "a 1.8 row wears the side's color in the name itself");

            List<UpdateScorePacket> scores = watcher.sent(UpdateScorePacket.class).stream()
                    .filter(u -> u.objectiveName().startsWith("rh-")).toList();
            assertTrue(scores.stream().anyMatch(u -> u.entityName().equals(row) && u.score() == 17),
                    "the recorded health lands on that row: " + scores);
            assertTrue(scores.stream().noneMatch(u -> u.entityName().equals("GhostHpActor")),
                    "and never under the bare name, which names no row here: " + scores);
        } finally {
            dressing.exit(watcher.player);
            dressing.close();
            playback.halt();
            watcher.player.remove();
        }
    }

    /** 1.9 has no list order either: it sorts rows by team, then profile name, exactly as 1.8 does. */
    @Test
    void watchersSinkBeforeListOrder() {
        byte[] file = recorded("SinkActor", new Pos(720.5, 65, 700.5));
        Shard theater = new Shard(instance);
        Playback playback = Replay.play(theater, file);
        Dressing dressing = new Dressing(theater, playback, "sinkwatch", "", LocalDate.now(), null, "tennacle",
                List.of("SinkActor|red|" + NamedTextColor.RED.value()), List.of(HealthDisplay.TAB));
        playback.ghostNames(dressing::listName);
        FakePlayer nine = FakePlayer.connect(instance, new Pos(724.5, 65, 700.5), "SinkNine");
        FakePlayer modern = FakePlayer.connect(instance, new Pos(726.5, 65, 700.5), "SinkModern");
        try {
            theater.join(nine.player);
            theater.join(modern.player);
            dressing.enter(nine.player, true);
            dressing.enter(modern.player, false);
            for (int i = 0; i < 4; i++) tick();

            assertTrue(watcherTeam(nine).contains(ShardTabList.listName(nine.player)),
                    "the watchers' team sorts past the lineup's");
            assertEquals(ShardTabList.listName(nine.player), ShardTabList.listName(nine.player, nine.player));
            Ghost ghost = playback.ghost("SinkActor");
            assertNotNull(ghost, "the move op raised the ghost");
            assertEquals("§cSinkActor", ghost.listedName(nine.player), "apart from a watcher sharing the name");

            assertTrue(watcherTeam(modern).isEmpty(), "1.21.2+ sinks the row on its list order instead");
            assertEquals("SinkModern", ShardTabList.listName(modern.player, modern.player));
            assertEquals("SinkActor", ghost.listedName(modern.player));
        } finally {
            dressing.exit(nine.player);
            dressing.exit(modern.player);
            dressing.close();
            playback.halt();
            nine.player.remove();
            modern.player.remove();
        }
    }

    // the entities of every watchers' team this client was told to create
    private static List<String> watcherTeam(FakePlayer viewer) {
        return viewer.sent(TeamsPacket.class).stream()
                .filter(t -> t.teamName().endsWith("~") && t.action() instanceof TeamsPacket.CreateTeamAction)
                .flatMap(t -> ((TeamsPacket.CreateTeamAction) t.action()).entities().stream())
                .toList();
    }
}
