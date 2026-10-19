package tennacle.storage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** A {@link Directory} that lasts as long as the process. */
public class MemoryDirectory implements Directory {

    private final Map<UUID, Entry> byId = new ConcurrentHashMap<>();
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();

    @Override
    public @NotNull CompletableFuture<@Nullable Entry> byId(@NotNull UUID id) {
        return CompletableFuture.completedFuture(byId.get(id));
    }

    @Override
    public @NotNull CompletableFuture<@Nullable Entry> byName(@NotNull String name) {
        UUID id = byName.get(key(name));
        return CompletableFuture.completedFuture(id != null ? byId.get(id) : null);
    }

    @Override
    public @NotNull CompletableFuture<Void> seen(@NotNull UUID id, @NotNull String name, long at) {
        put(new Entry(id, name, at));
        return CompletableFuture.completedFuture(null);
    }

    synchronized void put(Entry entry) {
        Entry before = byId.put(entry.id(), entry);
        if (before != null && !before.name().equalsIgnoreCase(entry.name())) byName.remove(key(before.name()), entry.id());
        byName.put(key(entry.name()), entry.id());
    }

    public synchronized @NotNull List<Entry> entries() {
        return List.copyOf(byId.values());
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
