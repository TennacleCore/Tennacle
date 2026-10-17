package tennacle.social.friends;

import io.github.term4.grotto.vault.VaultKey;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import org.jetbrains.annotations.NotNull;
import tennacle.PlayerPrefs;

import java.util.List;
import java.util.UUID;

/** Who is friends with whom, kept on both players' accounts. */
public final class Friends {

    /** {@code name}: theirs when the list was last written; {@code since}: epoch millis. */
    public record Friend(@NotNull UUID id, @NotNull String name, long since, boolean best) {
        static final Codec<Friend> CODEC = StructCodec.struct(
                "id", Codec.UUID, Friend::id,
                "name", Codec.STRING, Friend::name,
                "since", Codec.LONG, Friend::since,
                "best", Codec.BOOLEAN, Friend::best,
                Friend::new);
    }

    // written only through update: a set from a stale cache would drop the other side's write
    static final PlayerPrefs.Entry<List<Friend>, List<Friend>> LIST = PlayerPrefs.register(
            VaultKey.of("social:friends", Friend.CODEC.list(), List.of()));

    private Friends() {}

    /** Registers the list before the first login fetch. */
    public static void install() {}

    /** Whether the two are friends, as far as either one's loaded list says. */
    public static boolean are(@NotNull UUID a, @NotNull UUID b) {
        List<Friend> list = LIST.of(a);
        if (list != null) return list.stream().anyMatch(friend -> friend.id().equals(b));
        list = LIST.of(b);
        return list != null && list.stream().anyMatch(friend -> friend.id().equals(a));
    }
}
