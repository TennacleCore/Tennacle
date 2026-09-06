package tennacle.games.map;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.term4.barnacle.Arena;
import io.github.term4.bloom.Bloom;
import net.minestom.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Every {@code arenas/*.bncl} carrying {@code tag} is in the rotation - maps are named freely (urban,
 * sorcery), the tag decides which game they serve. Lazy like {@link HotArena}: an empty pool errors at
 * launch, never at boot, and {@code /arena save} adds, retags, or refreshes a map live (the NEXT game;
 * in-flight games finish on the arena they have).
 */
public final class MapPool implements Maps {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapPool.class);

    private final String tag;
    private final Map<Path, Arena> maps = new ConcurrentHashMap<>();
    private volatile boolean scanned;

    private MapPool(String tag) {
        this.tag = tag;
    }

    public static MapPool of(String tag) {
        MapPool pool = new MapPool(tag);
        Bloom.onSave(saved -> {
            // starter saves land in templates/ and never join a rotation
            Path parent = saved.getParent();
            if (parent != null && parent.equals(tennacle.TestServer.arenaDir())) pool.refresh(saved);
        });
        return pool;
    }

    @Override
    public Arena get() {
        if (!scanned) scan();
        maps.keySet().removeIf(file -> !Files.exists(file)); // /arena delete drops out of rotation
        List<Arena> live = List.copyOf(maps.values());
        if (live.isEmpty()) {
            throw new IllegalStateException("no arenas tagged '" + tag
                    + "' - build one in Bloom, /arena tag " + tag + ", and /arena save");
        }
        return live.get(ThreadLocalRandom.current().nextInt(live.size()));
    }

    @Override
    public @Nullable Arena named(@NotNull String name) {
        if (!scanned) scan();
        for (var entry : maps.entrySet()) {
            if (stem(entry.getKey()).equals(name) && Files.exists(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    @Override
    public @NotNull Set<String> names() {
        if (!scanned) scan();
        maps.keySet().removeIf(file -> !Files.exists(file));
        return maps.keySet().stream().map(MapPool::stem).collect(Collectors.toCollection(TreeSet::new));
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - ".bncl".length());
    }

    private synchronized void scan() {
        if (scanned) return;
        Path dir = tennacle.TestServer.arenaDir();
        try (var files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".bncl")).forEach(this::refresh);
            scanned = true; // a failed scan stays unscanned: a wrong working directory must not be permanent
        } catch (IOException e) {
            LOGGER.warn("could not read {} for the '{}' pool - retrying on the next launch", dir.toAbsolutePath(), tag);
        }
    }

    private void refresh(Path file) {
        Arena arena;
        try {
            arena = Arena.read(file);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("unreadable map {} for the '{}' pool", file, tag, e);
            return;
        }
        HotArena.drain(arena.meta().tags().contains(tag) ? maps.put(file, arena) : maps.remove(file));
        if (maps.containsKey(file)) System.out.println("[maps:" + tag + "] " + file.getFileName() + " in rotation");
    }
}
