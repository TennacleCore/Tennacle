package tennacle;

import io.github.term4.archipelago.testsupport.FakePlayer;
import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.vault.MemoryVault;
import io.github.term4.grotto.vault.VaultKey;
import net.minestom.server.codec.Codec;
import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PrefsTest extends HeadlessServerTest {

    private static final PlayerPrefs.Entry<Integer, Integer> COUNT =
            PlayerPrefs.register(VaultKey.of("test:count", Codec.INT, 0));

    @Test
    void anOfflineUpdateLands() throws Exception {
        MemoryVault vault = new MemoryVault();
        PlayerPrefs.vault(() -> vault);
        UUID away = UUID.randomUUID();
        COUNT.update(away, n -> n + 1).get(5, TimeUnit.SECONDS);
        assertEquals(2, COUNT.update(away, n -> n + 1).get(5, TimeUnit.SECONDS));
        assertEquals(2, COUNT.load(away).get(5, TimeUnit.SECONDS), "read back from the store");
        assertNull(COUNT.of(away), "nobody online, nothing cached");
    }

    @Test
    void aLateKeyLoadsForTheOnline() throws Exception {
        MemoryVault vault = new MemoryVault();
        PlayerPrefs.vault(() -> vault);
        PlayerPrefs.install();
        FakePlayer p = FakePlayer.connect(instance, new Pos(4.5, 65, 0.5), "LateKey");
        try {
            VaultKey<Integer> key = VaultKey.of("test:late", Codec.INT, 0);
            vault.set(p.player.getUuid(), key, 7).get(5, TimeUnit.SECONDS);
            assertEquals(7, PlayerPrefs.register(key).of(p.player.getUuid()), "registered after the login, still read");
        } finally {
            p.player.remove();
        }
    }

    /** Since 2026-09-21 every value read at login was dropped: the login fetch runs before the player counts as online. */
    @Test
    void theLoginFetchLands() {
        MemoryVault store = new MemoryVault();
        VaultKey<Integer> key = VaultKey.of("test:login", Codec.INT, 0);
        PlayerPrefs.Entry<Integer, Integer> saved = PlayerPrefs.register(key);
        PlayerPrefs.vault(() -> new io.github.term4.grotto.vault.Vault() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> java.util.concurrent.@org.jetbrains.annotations.NotNull CompletableFuture<T> get(@org.jetbrains.annotations.NotNull UUID player,
                    @org.jetbrains.annotations.NotNull VaultKey<T> asked) {
                if (asked.id().equals(key.id())) return java.util.concurrent.CompletableFuture.completedFuture((T) Integer.valueOf(7));
                return store.get(player, asked);
            }

            @Override
            public <T> java.util.concurrent.@org.jetbrains.annotations.NotNull CompletableFuture<Void> set(@org.jetbrains.annotations.NotNull UUID player,
                    @org.jetbrains.annotations.NotNull VaultKey<T> asked, @org.jetbrains.annotations.NotNull T value) {
                return store.set(player, asked, value);
            }
        });
        PlayerPrefs.install();
        FakePlayer p = FakePlayer.connect(instance, new Pos(6.5, 65, 0.5), "LoginKey");
        try {
            assertEquals(7, saved.of(p.player.getUuid()), "read during configuration, still kept");
        } finally {
            p.player.remove();
        }
    }
}
