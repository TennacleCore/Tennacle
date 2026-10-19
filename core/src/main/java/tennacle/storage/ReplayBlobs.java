package tennacle.storage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/** Bytes by key ({@code <mode>/<id>.shard}). A bucket in production; {@link FileReplayBlobs} until then. */
public interface ReplayBlobs {

    @NotNull CompletableFuture<Void> put(@NotNull String key, byte @NotNull [] bytes);

    @NotNull CompletableFuture<byte @Nullable []> get(@NotNull String key);

    @NotNull CompletableFuture<Void> delete(@NotNull String key);

    static @NotNull String key(@NotNull String mode, @NotNull String id) {
        return (mode.isBlank() ? "games" : mode) + "/" + id + ".shard";
    }
}
