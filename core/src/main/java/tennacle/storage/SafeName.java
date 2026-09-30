package tennacle.storage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * A command word turned into a file name. {@code ArgumentType.Word} rejects only spaces, so an unchecked name
 * escapes its directory ({@code ../../x}); every path built from user input goes through here.
 */
public final class SafeName {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,63}");

    private SafeName() {}

    public static boolean valid(@Nullable String name) {
        return name != null && ALLOWED.matcher(name).matches();
    }

    /** The named file inside {@code dir}, or {@code null} when the name is not one (refuse, don't sanitize). */
    public static @Nullable Path resolve(@NotNull Path dir, @Nullable String name, @NotNull String suffix) {
        if (!valid(name)) return null;
        Path base = dir.toAbsolutePath().normalize();
        Path file = base.resolve(name + suffix).normalize();
        return file.startsWith(base) ? file : null;
    }

    /** What to tell a player who named a file badly. */
    public static @NotNull String rule() {
        return "letters, digits, _ - . only (max 64, no leading dot)";
    }
}
