package tennacle.storage;

import io.github.term4.grotto.vault.Vault;
import io.github.term4.grotto.vault.VaultKey;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * The app's account store: one file per player under {@code <dir>/<uuid>.dat} holding the opaque wire bytes the
 * vault contract defines. The libraries persist nothing; this is where the server decides what durable means.
 *
 * <p>A player's file is read once and served from memory after, so a login is one read rather than one per key.
 * Writes are read-modify-write under that player's lock, which makes {@link #update} atomic where the interface
 * default is not, and land through {@link Atomic}. {@link #flush} is what a shutdown waits on.
 */
public final class FileVault implements Vault {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileVault.class);
    private static final int MAGIC = 0x54564C54; // TVLT
    private static final byte FORMAT = 1;

    private static final class Account {
        private final Map<String, byte[]> entries = new HashMap<>();
        private CompletableFuture<Void> pending = CompletableFuture.completedFuture(null);
    }

    private final Path dir;
    private final ExecutorService io;
    private final Map<UUID, Account> accounts = new ConcurrentHashMap<>();

    public FileVault(@NotNull Path dir) {
        this.dir = dir;
        this.io = Executors.newFixedThreadPool(2, r -> {
            Thread thread = new Thread(r, "tennacle-vault");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot use " + dir.toAbsolutePath() + " for player data", e);
        }
    }

    @Override
    public <T> @NotNull CompletableFuture<T> get(@NotNull UUID player, @NotNull VaultKey<T> key) {
        return CompletableFuture.supplyAsync(() -> {
            Account account = account(player);
            synchronized (account) {
                return key.decode(account.entries.get(key.id()));
            }
        }, io);
    }

    @Override
    public <T> @NotNull CompletableFuture<Void> set(@NotNull UUID player, @NotNull VaultKey<T> key, @NotNull T value) {
        return CompletableFuture.runAsync(() -> {
            Account account = account(player);
            synchronized (account) {
                account.entries.put(key.id(), key.encode(value));
                save(player, account);
            }
        }, io).whenComplete((ignored, failed) -> { // most callers drop this future: an encode or write failure is news
            if (failed != null) LOGGER.warn("vault write {} for {} failed", key.id(), player, failed);
        });
    }

    /** Atomic per player: the read, the function and the write all run under that account's lock. */
    @Override
    public <T> @NotNull CompletableFuture<T> update(@NotNull UUID player, @NotNull VaultKey<T> key,
                                                    @NotNull UnaryOperator<T> fn) {
        return CompletableFuture.supplyAsync(() -> {
            Account account = account(player);
            synchronized (account) {
                T updated = fn.apply(key.decode(account.entries.get(key.id())));
                account.entries.put(key.id(), key.encode(updated));
                save(player, account);
                return updated;
            }
        }, io);
    }

    /** Drops a player's cached file once their writes have landed; the next read loads it again. */
    public void forget(@NotNull UUID player) {
        Account account = accounts.get(player);
        if (account == null) return;
        CompletableFuture<Void> pending;
        synchronized (account) {
            pending = account.pending;
        }
        pending.whenComplete((done, error) -> {
            synchronized (account) {
                if (account.pending != pending) return; // a later write chained on: that one owns the eviction
            }
            accounts.remove(player, account);
        });
    }

    /** Waits out every pending write; {@code false} = something was still in flight when the wait ran out. */
    public boolean flush(long timeout, @NotNull TimeUnit unit) {
        CompletableFuture<?>[] writes = accounts.values().stream().map(account -> {
            synchronized (account) {
                return account.pending;
            }
        }).toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(writes).get(timeout, unit);
            return true;
        } catch (Exception e) {
            LOGGER.warn("player data did not finish writing", e);
            return false;
        }
    }

    public void close() {
        io.shutdown();
    }

    private Account account(UUID player) {
        return accounts.computeIfAbsent(player, id -> {
            Account account = new Account();
            Path file = dir.resolve(id + ".dat");
            if (!Files.isReadable(file)) return account;
            try {
                account.entries.putAll(read(Files.readAllBytes(file)));
            } catch (IOException | RuntimeException e) {
                // never serve a half-read account as an empty one: the next write would erase what survived
                throw new IllegalStateException("unreadable player data " + file.getFileName(), e);
            }
            return account;
        });
    }

    // the caller holds the account's lock
    private void save(UUID player, Account account) {
        byte[] bytes = write(account.entries);
        Path file = dir.resolve(player + ".dat");
        account.pending = account.pending.thenRunAsync(() -> {
            try {
                Atomic.write(file, bytes);
            } catch (IOException e) {
                LOGGER.error("could not save player data {}", file.getFileName(), e);
            }
        }, io);
    }

    /** One player's file as key id to stored bytes, for moving it to another backend. */
    public static @NotNull Map<String, byte[]> entries(@NotNull Path file) throws IOException {
        return read(Files.readAllBytes(file));
    }

    private static Map<String, byte[]> read(byte[] bytes) {
        Map<String, byte[]> entries = new HashMap<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) throw new IllegalStateException("not a vault file");
            byte format = in.readByte();
            if (format != FORMAT) throw new IllegalStateException("vault format " + format + ", expected " + FORMAT);
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                String id = in.readUTF();
                byte[] value = new byte[in.readInt()];
                in.readFully(value);
                entries.put(id, value);
            }
        } catch (IOException e) {
            throw new IllegalStateException("truncated vault file", e);
        }
        return entries;
    }

    private static byte[] write(Map<String, byte[]> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeByte(FORMAT);
            out.writeInt(entries.size());
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeInt(entry.getValue().length);
                out.write(entry.getValue());
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot encode player data", e);
        }
        return bytes.toByteArray();
    }
}
