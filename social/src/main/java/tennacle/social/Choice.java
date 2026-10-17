package tennacle.social;

import io.github.term4.grotto.vault.VaultKey;
import net.minestom.server.codec.Codec;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import tennacle.PlayerPrefs;
import tennacle.social.blocks.Blocks;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.Locale;

/** One {@link Audience} among {@code options}, kept with a player's preferences by name; anything unreadable is the first. */
public final class Choice {

    private final List<Audience> options;
    private final PlayerPrefs.Entry<String, Audience> stored;

    private Choice(String key, List<Audience> options) {
        this.options = List.copyOf(options);
        this.stored = PlayerPrefs.register(VaultKey.of(key, Codec.STRING, options.getFirst().name()),
                name -> read(name, options), audience -> audience.name());
    }

    /** A choice stored under {@code key}; register it before the first login so the login fetch carries it. */
    public static @NotNull Choice of(@NotNull String key, @NotNull Audience... options) {
        return new Choice(key, List.of(options));
    }

    public @NotNull List<Audience> options() {
        return options;
    }

    public @NotNull Audience of(@NotNull Player player) {
        Audience held = stored.of(player.getUuid());
        return held != null ? held : options.getFirst();
    }

    /** What {@code player} chose, online or not. */
    public @NotNull CompletableFuture<Audience> load(@NotNull UUID player) {
        return stored.load(player);
    }

    /** Whether {@code from} reaches {@code to}: never across a block, else as {@code to} chose. */
    public boolean admits(@NotNull Player from, @NotNull Player to) {
        return !Blocks.between(from.getUuid(), to.getUuid()) && of(to).admits(from, to);
    }

    public void set(@NotNull Player player, @NotNull Audience audience) {
        stored.set(player, audience);
    }

    /** The option {@code step} along from the current one, wrapping. */
    public @NotNull Audience step(@NotNull Player player, int step) {
        int at = options.indexOf(of(player));
        Audience next = options.get(Math.floorMod(at + step, options.size()));
        set(player, next);
        return next;
    }

    private static Audience read(String name, List<Audience> options) {
        try {
            Audience audience = Audience.valueOf(name.toUpperCase(Locale.ROOT));
            return options.contains(audience) ? audience : options.getFirst();
        } catch (IllegalArgumentException unreadable) {
            return options.getFirst();
        }
    }
}
