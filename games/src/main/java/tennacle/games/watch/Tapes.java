package tennacle.games.watch;

import tennacle.storage.ReplayStore;
import io.github.term4.archipelago.replay.Replay;
import io.github.term4.archipelago.storage.ShardFile;
import io.github.term4.archipelago.storage.UserData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Where a replay lives: finished, in the {@link ReplayStore}; while the game runs, as a spool in
 * {@code <mode>/spool/} under the local replay directory. The spool is gone once the replay is kept; a game a
 * stop cuts short still lands ({@link #flush} waits for it), and one a crash cuts leaves its spool for the next
 * boot to keep ({@link #install}). Nothing here knows what a game is.
 */
public final class Tapes {

    private static final Logger LOGGER = LoggerFactory.getLogger(Tapes.class);

    static final int SPOOL_TICKS = 1200;

    private static volatile @Nullable Path dir;
    private static volatile @Nullable ReplayStore store;

    private static final Set<CompletableFuture<?>> landing = ConcurrentHashMap.newKeySet();

    private Tapes() {}

    /** Sets the directory and keeps whatever a crash left spooled under it. */
    static void install(@NotNull Path replays, @NotNull ReplayStore replayStore) {
        dir = replays;
        store = replayStore;
        sweep(replays);
    }

    public static @NotNull ReplayStore store() {
        ReplayStore installed = store;
        if (installed == null) throw new IllegalStateException("Tapes.install first");
        return installed;
    }

    /** Every finished replay in the local folders, described from its own header: what a new index starts from. */
    public static @NotNull List<ReplayStore.Entry> onDisk(@NotNull Path replays) {
        List<ReplayStore.Entry> out = new ArrayList<>();
        if (!Files.isDirectory(replays)) return out;
        try (Stream<Path> folders = Files.list(replays)) {
            for (Path folder : folders.filter(Files::isDirectory).toList()) {
                try (Stream<Path> files = Files.list(folder)) {
                    for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".shard")).toList()) {
                        String id = file.getFileName().toString().replaceFirst("\\.shard$", "");
                        try {
                            byte[] bytes = Files.readAllBytes(file);
                            out.add(entry(id, folder.getFileName().toString(), ShardFile.userData(bytes),
                                    Files.getLastModifiedTime(file).toMillis(), bytes.length));
                        } catch (IOException | RuntimeException unreadable) {
                            LOGGER.warn("{} was left out of the replay index", file, unreadable);
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("the replay directory {} was not read", replays, e);
        }
        return out;
    }

    static ReplayStore.Entry entry(String id, String mode, UserData.View data, long playedAt, long size) {
        String code = data.get(Replays.CODE);
        String map = data.get(Replays.MAP);
        return new ReplayStore.Entry(id, code == null ? "" : code, mode, map == null ? "" : map, playedAt, 0, size);
    }

    /** Where a game's spool grows while it plays, or null until {@link #install}. */
    static @Nullable Path spoolOf(@NotNull String gameId, @Nullable String mode) {
        Path at = dir;
        return at == null ? null : at.resolve(folderOf(mode)).resolve("spool").resolve(gameId + ".spool");
    }

    /** Holds {@code write} against {@link #flush} until it lands. */
    static void landing(@NotNull CompletableFuture<?> write) {
        landing.add(write);
        write.whenComplete((v, e) -> landing.remove(write));
    }

    /** Waits for the files of games that just ended to land; {@code false} = one was still in flight. */
    public static boolean flush(long timeout, @NotNull TimeUnit unit) {
        try {
            CompletableFuture.allOf(landing.toArray(CompletableFuture[]::new)).get(timeout, unit);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
    }

    static String folderOf(@Nullable String mode) {
        return mode == null || mode.isBlank() ? "games" : mode;
    }

    // what an earlier run left behind: the spool of a game a crash cut short becomes its file, and a file from
    // before the mode folders moves under its mode
    private static void sweep(Path at) {
        if (!Files.isDirectory(at)) return;
        List<Path> spools = new ArrayList<>();
        List<Path> loose = new ArrayList<>();
        try (Stream<Path> entries = Files.list(at)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (Files.isRegularFile(entry) && name.endsWith(".shard")) loose.add(entry);
                else if (Files.isDirectory(entry)) spools.addAll(spoolsIn(name.equals("spool") ? entry : entry.resolve("spool")));
            }
        } catch (IOException e) {
            LOGGER.warn("the replay directory {} was not swept", at, e);
            return;
        }
        for (Path spool : spools) salvage(at, spool);
        for (Path file : loose) file(at, file);
        try {
            Files.deleteIfExists(at.resolve("spool")); // the folder from before the mode folders, once emptied
        } catch (IOException ignored) {
            // still holds something: stays
        }
    }

    private static List<Path> spoolsIn(Path folder) {
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.filter(f -> f.getFileName().toString().endsWith(".spool")).toList();
        } catch (IOException e) {
            LOGGER.warn("{} was not swept", folder, e);
            return List.of();
        }
    }

    // a spool that fails to salvage goes too: it would fail the same way every boot
    private static void salvage(Path at, Path spool) {
        String id = spool.getFileName().toString().replaceFirst("\\.spool$", "");
        try {
            byte[] bytes = Replay.salvage(spool);
            if (bytes == null) {
                LOGGER.warn("the spool of game {} held nothing to keep; dropped", id);
            } else {
                UserData.View data = ShardFile.userData(bytes);
                store().put(entry(id, folderOf(data.get(Replays.MODE)), data, Files.getLastModifiedTime(spool).toMillis(),
                        bytes.length), bytes).join();
                LOGGER.info("kept the replay of game {} from its spool, cut where the last run stopped", id);
            }
            Files.deleteIfExists(spool);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("the spool of game {} could not be salvaged; dropped", id, e);
            try {
                Files.deleteIfExists(spool);
            } catch (IOException ignored) {
                // it stays; the next boot says so again
            }
        }
    }

    private static void file(Path at, Path file) {
        try {
            UserData.View data = ShardFile.userData(Files.readAllBytes(file));
            if (data.get(Replay.OPS) == null) return; // a plain shard save someone dropped here: not ours to move
            Path to = at.resolve(folderOf(data.get(Replays.MODE))).resolve(file.getFileName().toString());
            Files.createDirectories(to.getParent());
            Files.move(file, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("{} stays where it is", file, e);
        }
    }
}
