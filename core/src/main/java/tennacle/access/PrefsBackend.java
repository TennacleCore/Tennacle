package tennacle.access;

import io.github.term4.grotto.vault.VaultKey;
import org.jetbrains.annotations.NotNull;
import tennacle.PlayerPrefs;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

/** Grants on the player's own account, loaded with the rest of their preferences at login. */
final class PrefsBackend implements Access.Backend {

    private static final PlayerPrefs.Entry<Grants, Grants> GRANTS =
            PlayerPrefs.register(VaultKey.of("access:grants", Grants.CODEC, Grants.NONE));

    static void install() {}

    @Override
    public boolean holds(@NotNull UUID player, @NotNull String node) {
        Grants grants = GRANTS.of(player);
        return grants != null && Access.matches(Access.effective(grants), node);
    }

    @Override
    public @NotNull CompletableFuture<Grants> grants(@NotNull UUID player) {
        return GRANTS.load(player);
    }

    @Override
    public @NotNull CompletableFuture<Grants> change(@NotNull UUID player, @NotNull UnaryOperator<Grants> change) {
        return GRANTS.update(player, change);
    }
}
