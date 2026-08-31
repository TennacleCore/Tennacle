package tennacle.games;

import io.github.term4.barnacle.Arena;
import io.github.term4.bloom.Bloom;
import net.minestom.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Every {@code arenas/*.bncl} carrying {@code tag} is in the rotation - maps are named freely (urban,
 * sorcery), the tag decides which game they serve. Lazy like {@link HotArena}: an empty pool errors at
 * launch, never at boot, and {@code /arena save} adds, retags, or refreshes a map live (the NEXT game;
 * in-flight games finish on the arena they have).
 */
public final class MapPool implements Supplier<Arena> {

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
            if (parent != null && parent.getFileName().toString().equals("arenas")) pool.refresh(saved);
        });
        return pool;
    }

    @Override
    public Arena get() {
        if (!scanned) scan();
        List<Arena> live = List.copyOf(maps.values());
        if (live.isEmpty()) {
            throw new IllegalStateException("no arenas tagged '" + tag
                    + "' - build one in Bloom, /arena tag " + tag + ", and /arena save");
        }
        return live.get(ThreadLocalRandom.current().nextInt(live.size()));
    }

    private synchronized void scan() {
        if (scanned) return;
        try (var files = Files.list(Path.of("arenas"))) {
            files.filter(f -> f.getFileName().toString().endsWith(".bncl")).forEach(this::refresh);
        } catch (IOException ignored) { // no arenas dir yet - the pool just stays empty
        }
        scanned = true;
    }

    private void refresh(Path file) {
        Arena arena;
        try {
            arena = Arena.read(file);
        } catch (IOException | RuntimeException e) {
            System.err.println("[maps:" + tag + "] unreadable " + file + ": " + e);
            return;
        }
        drain(arena.meta().tags().contains(tag) ? maps.put(file, arena) : maps.remove(file));
        if (maps.containsKey(file)) System.out.println("[maps:" + tag + "] " + file.getFileName() + " in rotation");
    }

    private static void drain(Arena old) {
        var stale = old == null ? null : old.instantiated();
        if (stale == null) return;
        MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
            boolean inUse = !stale.getPlayers().isEmpty()
                    || Duels.grotto().games().stream().anyMatch(g -> g.instance() == stale);
            if (!inUse) MinecraftServer.getInstanceManager().unregisterInstance(stale);
        });
    }
}
