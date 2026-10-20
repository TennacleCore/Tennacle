package tennacle.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** The connection pool, the schema, and where every query runs: off the caller's thread. */
public final class Database implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(Database.class);
    private static final String[] MIGRATIONS = {"V1.sql", "V2.sql", "V3.sql", "V4.sql", "V5.sql", "V6.sql"};
    private static final long MIGRATION_LOCK = 0x54454E4E; // TENN

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private final HikariDataSource pool;
    private final ExecutorService io = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tennacle-db-", 0).factory());
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();

    private Database(HikariDataSource pool) {
        this.pool = pool;
    }

    /** Connects and brings the schema up to date; throws when the server can't be reached. */
    public static @NotNull Database connect(@NotNull String url, @NotNull String user, @Nullable String password, int size) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        if (password != null) config.setPassword(password);
        config.setMaximumPoolSize(size);
        config.setPoolName("tennacle-db");
        Database db = new Database(new HikariDataSource(config));
        try {
            db.migrate();
        } catch (SQLException | IOException e) {
            db.close();
            throw new IllegalStateException("could not bring the database schema up to date", e);
        }
        return db;
    }

    public <T> @NotNull CompletableFuture<T> query(@NotNull Work<T> work) {
        return track(CompletableFuture.supplyAsync(() -> {
            try (Connection connection = pool.getConnection()) {
                return work.run(connection);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }, io));
    }

    /** All of {@code work} or none of it. */
    public <T> @NotNull CompletableFuture<T> transaction(@NotNull Work<T> work) {
        return query(connection -> inTransaction(connection, work));
    }

    static <T> T inTransaction(Connection connection, Work<T> work) throws SQLException {
        connection.setAutoCommit(false);
        try {
            T result = work.run(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /** Waits out the queries in flight; {@code false} = something was still running. */
    public boolean flush(long timeout, @NotNull TimeUnit unit) {
        try {
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(timeout, unit);
            return true;
        } catch (Exception e) {
            LOGGER.warn("database writes did not finish", e);
            return false;
        }
    }

    @Override
    public void close() {
        io.shutdown();
        pool.close();
    }

    private <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        pending.add(future);
        future.whenComplete((done, failed) -> pending.remove(future));
        return future;
    }

    private void migrate() throws SQLException, IOException {
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            // the game server and the API service may start together
            statement.execute("SELECT pg_advisory_lock(" + MIGRATION_LOCK + ")");
            try {
                migrate(connection, statement);
            } finally {
                statement.execute("SELECT pg_advisory_unlock(" + MIGRATION_LOCK + ")");
            }
        }
    }

    private static void migrate(Connection connection, Statement statement) throws SQLException, IOException {
        statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version int PRIMARY KEY)");
        int at;
        try (var rows = statement.executeQuery("SELECT coalesce(max(version), 0) FROM schema_version")) {
            rows.next();
            at = rows.getInt(1);
        }
        for (int version = at + 1; version <= MIGRATIONS.length; version++) {
            String sql = resource(MIGRATIONS[version - 1]);
            int applying = version;
            inTransaction(connection, c -> {
                try (Statement s = c.createStatement()) {
                    s.execute(sql);
                    s.execute("INSERT INTO schema_version VALUES (" + applying + ")");
                }
                return null;
            });
            LOGGER.info("database schema at version {}", version);
        }
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = Database.class.getResourceAsStream(name)) {
            if (in == null) throw new IOException("missing migration " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
