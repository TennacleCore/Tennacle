package tennacle.net;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** What the processes tell each other. */
public final class Topics {

    /**
     * A ban or mute was added or lifted: wherever the player is, their server reads it again. {@code origin} is the
     * process that made the change, which already acted on it.
     */
    public record Punished(@NotNull String origin, @NotNull UUID player, @NotNull String kind) {}

    /** {@code reason} as the kick screen shows it. */
    public record Kicked(@NotNull String origin, @NotNull UUID player, @NotNull String reason) {}

    /** Their role or grants changed. */
    public record Regranted(@NotNull String origin, @NotNull UUID player) {}

    /** A private message for a player on another server; {@code fromStaff} sees through vanish. */
    public record Whisper(@NotNull String id, @NotNull UUID from, @NotNull String fromName, boolean fromStaff,
                          @NotNull String fromServer, @NotNull UUID to, @NotNull String text) {}

    /** The answer to a {@link Whisper}: {@code refused} null when it was delivered. */
    public record Whispered(@NotNull String id, @NotNull String toServer, @NotNull String toName, @Nullable String refused) {}

    /** Their API keys stopped working: an API process forgets what it cached. */
    public record KeyRevoked(@NotNull UUID owner) {}

    public static final Topic<Punished> PUNISHED = new Topic<>("tennacle:punished", Punished.class);
    public static final Topic<Kicked> KICKED = new Topic<>("tennacle:kicked", Kicked.class);
    public static final Topic<Regranted> REGRANTED = new Topic<>("tennacle:regranted", Regranted.class);
    public static final Topic<Whisper> WHISPER = new Topic<>("tennacle:whisper", Whisper.class);
    public static final Topic<Whispered> WHISPERED = new Topic<>("tennacle:whispered", Whispered.class);
    public static final Topic<KeyRevoked> KEY_REVOKED = new Topic<>("tennacle:key-revoked", KeyRevoked.class);

    private Topics() {}
}
