package tennacle.moderation;

import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import org.jetbrains.annotations.NotNull;

/** {@code until} in epoch millis. */
public record Mute(long until, @NotNull String reason, @NotNull String by) {

    public static final Mute NONE = new Mute(0, "", "");

    static final Codec<Mute> CODEC = StructCodec.struct(
            "until", Codec.LONG, Mute::until,
            "reason", Codec.STRING, Mute::reason,
            "by", Codec.STRING, Mute::by,
            Mute::new);

    public boolean active(long now) {
        return until > now;
    }
}
