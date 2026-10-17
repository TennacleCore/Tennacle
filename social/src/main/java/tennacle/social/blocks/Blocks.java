package tennacle.social.blocks;

import io.github.term4.grotto.vault.VaultKey;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import org.jetbrains.annotations.NotNull;
import tennacle.PlayerPrefs;

import java.util.List;
import java.util.UUID;

/** Who each player has blocked. A block cuts both ways: neither reaches the other. */
public final class Blocks {

    public record Blocked(@NotNull UUID id, @NotNull String name) {
        static final Codec<Blocked> CODEC = StructCodec.struct(
                "id", Codec.UUID, Blocked::id,
                "name", Codec.STRING, Blocked::name,
                Blocked::new);
    }

    static final PlayerPrefs.Entry<List<Blocked>, List<Blocked>> LIST = PlayerPrefs.register(
            VaultKey.of("social:blocked", Blocked.CODEC.list(), List.of()));

    private Blocks() {}

    /** Registers the list before the first login fetch. */
    public static void install() {}

    /** Whether {@code by} has blocked {@code who}, as far as {@code by}'s loaded list says. */
    public static boolean blocked(@NotNull UUID by, @NotNull UUID who) {
        List<Blocked> list = LIST.of(by);
        return list != null && list.stream().anyMatch(blocked -> blocked.id().equals(who));
    }

    /** Whether either has blocked the other. */
    public static boolean between(@NotNull UUID a, @NotNull UUID b) {
        return blocked(a, b) || blocked(b, a);
    }
}
