package tennacle.server;

import io.github.term4.grotto.vault.Vault;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minestom.server.MinecraftServer;
import net.minestom.server.timer.TaskSchedule;
import tennacle.boot.Config;
import tennacle.games.watch.Tapes;
import tennacle.moderation.Punishments;
import tennacle.moderation.VaultPunishments;
import tennacle.postgres.Database;
import tennacle.postgres.Imports;
import tennacle.postgres.PgDirectory;
import tennacle.postgres.PgMatches;
import tennacle.postgres.PgPunishments;
import tennacle.postgres.PgReplays;
import tennacle.postgres.PgStats;
import tennacle.postgres.PgVault;
import tennacle.storage.Directory;
import tennacle.storage.FileDirectory;
import tennacle.storage.FileMatchStore;
import tennacle.storage.FileReplayBlobs;
import tennacle.storage.FileReplays;
import tennacle.storage.FileVault;
import tennacle.storage.MatchStore;
import tennacle.storage.ReplayStore;
import tennacle.storage.StatKeys;
import tennacle.storage.StatRouting;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Where durable data lives: PostgreSQL when {@code tennacle.postgres.url} is set, the files under data/ otherwise. */
record Stores(@NotNull Vault vault, @NotNull Directory directory, @NotNull MatchStore matches,
              @NotNull Punishments punishments, @NotNull ReplayStore replays, @NotNull Runnable installed,
              @NotNull Closer closer) {

    private static final Logger LOGGER = LoggerFactory.getLogger(Stores.class);

    interface Closer {
        void left(@NotNull UUID player);

        /** Waits out pending writes, then lets go. */
        void close(long timeout, @NotNull TimeUnit unit);
    }

    static @NotNull Stores open(@NotNull Path data, @NotNull Path replayDir) {
        String url = Config.value("tennacle.postgres.url");
        if (url == null) return files(data, replayDir);
        Database db = Database.connect(url, Config.string("tennacle.postgres.user", "tennacle"),
                Config.value("tennacle.postgres.password"), Config.integer("tennacle.postgres.pool", 8));
        Imports.filesOnce(db, data);
        LOGGER.info("storage: postgres");
        PgStats stats = new PgStats(db);
        long keep = TimeUnit.DAYS.toMillis(Config.integer("tennacle.replays.keep-days", 0)); // 0: kept forever
        PgReplays replays = new PgReplays(db, new FileReplayBlobs(replayDir), keep);
        // once every mode has named its career keys
        Runnable installed = () -> {
            stats.adopt(StatKeys.all()).join();
            replays.adoptOnce(() -> Tapes.onDisk(replayDir)).join();
            MinecraftServer.getSchedulerManager().buildTask(() -> replays.expire(System.currentTimeMillis()))
                    .repeat(TaskSchedule.hours(1)).schedule();
        };
        return new Stores(new StatRouting(new PgVault(db), stats), new PgDirectory(db), new PgMatches(db),
                new PgPunishments(db), replays, installed, new Closer() {
            @Override
            public void left(@NotNull UUID player) {}

            @Override
            public void close(long timeout, @NotNull TimeUnit unit) {
                db.flush(timeout, unit);
                db.close();
            }
        });
    }

    private static Stores files(Path data, Path replayDir) {
        FileVault vault = new FileVault(data.resolve("players"));
        FileDirectory directory = new FileDirectory(data.resolve("directory.tsv"));
        FileMatchStore matches = new FileMatchStore(data.resolve("matches"));
        LOGGER.info("storage: files under {}", data.toAbsolutePath());
        return new Stores(vault, directory, matches, new VaultPunishments(() -> vault), new FileReplays(replayDir),
                () -> {}, new Closer() {
            @Override
            public void left(@NotNull UUID player) {
                vault.forget(player);
            }

            @Override
            public void close(long timeout, @NotNull TimeUnit unit) {
                vault.flush(timeout, unit);
                vault.close();
                directory.flush(timeout, unit);
                directory.close();
                matches.flush(timeout, unit);
            }
        });
    }
}
