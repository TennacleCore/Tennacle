package tennacle.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** One public API key per player, kept only as a hash: the key itself is shown once, when it is made. */
public interface ApiKeys {

    /** Makes {@code owner} a new key, revoking any older one. */
    @NotNull CompletableFuture<String> issue(@NotNull UUID owner, long now);

    /** False when they had none. */
    @NotNull CompletableFuture<Boolean> revoke(@NotNull UUID owner, long now);

    /** Whose live key this is, or null. */
    @NotNull CompletableFuture<@Nullable UUID> owner(@NotNull String key);

    /** Without a database there is no API to call. */
    ApiKeys NONE = new ApiKeys() {
        @Override
        public @NotNull CompletableFuture<String> issue(@NotNull UUID owner, long now) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("the API needs the database"));
        }

        @Override
        public @NotNull CompletableFuture<Boolean> revoke(@NotNull UUID owner, long now) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public @NotNull CompletableFuture<@Nullable UUID> owner(@NotNull String key) {
            return CompletableFuture.completedFuture(null);
        }
    };

    static @NotNull String generate() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return "tn_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static @NotNull String hash(@NotNull String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
