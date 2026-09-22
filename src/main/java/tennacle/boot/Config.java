package tennacle.boot;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Boot settings, in order: system property, environment variable ({@code tennacle.game-secret} ->
 * {@code TENNACLE_GAME_SECRET}), {@code tennacle.properties} beside the working directory, then the default.
 * A malformed value is a boot failure, not a silent fallback.
 */
public final class Config {

    private static final Logger LOGGER = LoggerFactory.getLogger(Config.class);
    private static final Properties FILE = new Properties();

    static {
        Path path = Path.of(System.getProperty("tennacle.config", "tennacle.properties"));
        if (Files.isReadable(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                FILE.load(in);
                LOGGER.info("config: {} ({} entries)", path.toAbsolutePath(), FILE.size());
            } catch (IOException e) {
                throw new IllegalStateException("unreadable " + path.toAbsolutePath(), e);
            }
        }
    }

    private Config() {}

    /** {@code null} when unset anywhere - the caller decides whether that is fatal. */
    public static @Nullable String value(@NotNull String key) {
        String property = System.getProperty(key);
        if (property != null && !property.isBlank()) return property.trim();
        String env = System.getenv(key.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT));
        if (env != null && !env.isBlank()) return env.trim();
        String file = FILE.getProperty(key);
        return file != null && !file.isBlank() ? file.trim() : null;
    }

    public static @NotNull String string(@NotNull String key, @NotNull String fallback) {
        String value = value(key);
        return value != null ? value : fallback;
    }

    public static int integer(@NotNull String key, int fallback) {
        String value = value(key);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(key + " must be a number, got '" + value + "'");
        }
    }

    public static boolean flag(@NotNull String key, boolean fallback) {
        String value = value(key);
        if (value == null) return fallback;
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) return Boolean.parseBoolean(value);
        throw new IllegalStateException(key + " must be true or false, got '" + value + "'");
    }

    public static @NotNull Path path(@NotNull String key, @NotNull String fallback) {
        return Path.of(string(key, fallback));
    }
}
