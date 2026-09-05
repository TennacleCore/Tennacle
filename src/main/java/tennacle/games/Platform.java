package tennacle.games;

import org.jetbrains.annotations.NotNull;

/** What a player says ({@code hypixel}): one ruleset name paired with one look name - the only place the two meet. */
public record Platform(@NotNull String name, @NotNull String ruleset, @NotNull String look) {

    /** What a match runs as when no preset is asked for: hypixel's rules under the scrims look. */
    public static final Platform DEFAULT = new Platform("default", "hypixel", "scrims");

    /** A platform whose ruleset and look share its name - the three measured ones. */
    public static @NotNull Platform of(@NotNull String name) {
        return new Platform(name, name, name);
    }
}
