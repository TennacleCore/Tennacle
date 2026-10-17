package tennacle;

import io.github.term4.archipelago.testsupport.HeadlessServerTest;
import io.github.term4.grotto.vault.MemoryVault;
import io.github.term4.grotto.vault.VaultKey;
import net.minestom.server.codec.Codec;
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
}
