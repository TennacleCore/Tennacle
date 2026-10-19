package tennacle.net;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class LocalPresence implements Presence {

    private final Map<UUID, Here> online = new ConcurrentHashMap<>();

    @Override
    public @NotNull CompletableFuture<Void> joined(@NotNull Here here) {
        online.put(here.player(), here);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public @NotNull CompletableFuture<Void> left(@NotNull UUID player, @NotNull String server) {
        online.computeIfPresent(player, (id, here) -> here.server().equals(server) ? null : here);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public @NotNull CompletableFuture<@Nullable Here> where(@NotNull UUID player) {
        return CompletableFuture.completedFuture(online.get(player));
    }

    @Override
    public @NotNull CompletableFuture<Map<String, Integer>> servers() {
        Map<String, Integer> out = new TreeMap<>();
        for (Here here : online.values()) out.merge(here.server(), 1, Integer::sum);
        return CompletableFuture.completedFuture(out);
    }
}
