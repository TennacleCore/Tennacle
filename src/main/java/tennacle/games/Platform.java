package tennacle.games;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a player says ({@code hypixel}): one ruleset name paired with one look name - the only place the two meet.
 * {@code mechanics} names the Polyp preset the fold starts from when it is not the ruleset's own; null keeps the
 * ruleset's.
 */
public record Platform(@NotNull String name, @NotNull String ruleset, @NotNull String look, @Nullable String mechanics) {

    /** What a match runs as when no preset is asked for: scrims' rules and look on hypixel's mechanics. */
    public static final Platform DEFAULT = new Platform("default", "scrims", "scrims", "hypixel");

    /** A platform whose ruleset and look share its name, its mechanics the ruleset's - the three measured ones. */
    public static @NotNull Platform of(@NotNull String name) {
        return new Platform(name, name, name, null);
    }
}
