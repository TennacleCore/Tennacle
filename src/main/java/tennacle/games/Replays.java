package tennacle.games;

import tennacle.Msg;
import tennacle.storage.Atomic;

import io.github.term4.archipelago.replay.Recording;
import io.github.term4.archipelago.replay.Replay;
import io.github.term4.canvas.presets.Versus;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.roster.Roster;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every game is recorded: the log spools to disk as it grows and lands as {@code <game id>.shard} in the replay
 * directory when the game ends. Everyone who held a seat then hears it is there to watch, wherever they are by
 * then. The file is what a later store takes; the spool beside it is gone once the file is kept.
 */
public final class Replays {

    private static final Logger LOGGER = LoggerFactory.getLogger(Replays.class);
    private static final int SPOOL_TICKS = 1200;
    private static volatile @Nullable Path dir;
    private static final Map<String, Recording> live = new ConcurrentHashMap<>();

    private Replays() {}

    public static void install(@NotNull Path replays) {
        dir = replays;
        Matches.grotto().onGameEnd(Replays::ended);
    }

    /** The replay directory, once installed. */
    public static @Nullable Path dir() {
        return dir;
    }

    /** {@code <game id>.shard} under the replay directory. */
    public static @Nullable Path fileOf(@NotNull String gameId) {
        Path at = dir;
        return at == null ? null : at.resolve(gameId + ".shard");
    }

    /** Called at launch; a no-op until {@link #install}. */
    static void record(@NotNull Game game) {
        Path at = dir;
        if (at == null) return;
        try {
            Recording recording = Replay.record(game.shard())
                    .spool(at.resolve("spool").resolve(game.id() + ".spool"), SPOOL_TICKS);
            live.put(game.id(), recording);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("game {} is not being recorded", game.id(), e);
        }
    }

    /** Whether {@code game} is being recorded. */
    public static boolean recording(@NotNull Game game) {
        return live.containsKey(game.id());
    }

    private static void ended(Game game, Outcome outcome) {
        Recording recording = live.remove(game.id());
        Path at = dir;
        if (recording == null || at == null) return;
        // read now: the ledger and roster go with the game right after this
        String map = game.arena().meta().name();
        long ticks = game.tick();
        List<UUID> seated = game.roster().participants().stream().map(Roster.Participant::uuid).distinct().toList();
        Component line = recordedLine(game.code(), map, ticks);
        recording.stopAsync().thenAccept(bytes -> {
            if (bytes == null) return; // the encode failed and logged
            try {
                Files.createDirectories(at);
                Atomic.write(at.resolve(game.id() + ".shard"), bytes);
                recording.discard();
            } catch (IOException e) {
                LOGGER.warn("the replay of game {} was not saved", game.id(), e);
                return;
            }
            for (UUID uuid : seated) {
                Player player = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid);
                if (player != null) player.sendMessage(line);
            }
        });
    }

    /** Scrims' line: green, a click puts the watch command in chat, the hover names the map and the length. */
    static @NotNull Component recordedLine(@NotNull String code, @NotNull String map, long ticks) {
        Component hover = Msg.THEME.prose("Click to watch replay of game on ").append(Msg.v(map))
                .append(Msg.THEME.prose(" for " + clock(ticks)));
        return Versus.recordedLine(Msg.THEME, "/replay watch " + code, hover);
    }

    public static @NotNull String clock(long ticks) {
        long seconds = Math.max(0, ticks) / 20;
        return seconds / 60 + ":" + "%02d".formatted(seconds % 60);
    }
}
