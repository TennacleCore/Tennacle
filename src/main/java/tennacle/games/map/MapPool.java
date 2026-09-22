package tennacle.games.map;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.term4.barnacle.Arena;
import io.github.term4.grotto.GameType;
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
 *
 * <p>A map is only in rotation while it MEETS its type's expectations. A half-built one - markers still to
 * place - is held out with a line saying what it wants, because the alternative is every match that draws it
 * dying at launch. {@code /arena save} re-checks, so finishing the map puts it back.
 */
public final class MapPool implements Maps {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapPool.class);

    private final String tag;
    private final Map<Path, Arena> maps = new ConcurrentHashMap<>();
    /** What each held-out map still wants, by name - what {@link #get} says when the rotation is empty. */
    private final Map<String, String> unfinished = new ConcurrentHashMap<>();
    private volatile boolean scanned;
    private volatile GameType type;

    private MapPool(String tag) {
        this.tag = tag;
    }

    public static MapPool of(String tag) {
        MapPool pool = new MapPool(tag);
        Bloom.onSave(saved -> {
            // starter saves land in templates/ and never join a rotation
            Path parent = saved.getParent();
            if (parent != null && parent.equals(tennacle.boot.TestServer.arenaDir())) pool.refresh(saved);
        });
        return pool;
    }

    /** The type a map must satisfy to enter the rotation; unset, every tagged map is taken as it is. */
    public @NotNull MapPool serves(@NotNull GameType type) {
        this.type = type;
        return this;
    }

    /** What a mode needs beyond the marker counts: the phrase a map still wants, or {@code null} when ready. */
    @FunctionalInterface
    public interface Fitness {
        @Nullable String wants(@NotNull Arena arena);
    }

    private final java.util.List<Fitness> checks = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Adds a mode's own fitness check, run at every refresh alongside the marker counts. A map that fails one is
     * held out of the rotation with that phrase instead of failing at launch or playing wrong.
     */
    public @NotNull MapPool requires(@NotNull Fitness check) {
        checks.add(check);
        return this;
    }

    @Override
    public Arena get() {
        if (!scanned) scan();
        maps.keySet().removeIf(file -> !Files.exists(file)); // /arena delete drops out of rotation
        List<Arena> live = List.copyOf(maps.values());
        if (live.isEmpty()) {
            throw new IllegalStateException(unfinished.isEmpty()
                    ? "no arenas tagged '" + tag + "' - build one in Bloom, /arena tag " + tag + ", and /arena save"
                    : "no finished arenas tagged '" + tag + "' - " + unfinished.entrySet().stream()
                            .map(e -> e.getKey() + " wants " + e.getValue()).collect(Collectors.joining("; ")));
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
    public @NotNull Map<String, String> holdouts() {
        if (!scanned) scan();
        return Map.copyOf(unfinished);
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
        Path dir = tennacle.boot.TestServer.arenaDir();
        try (var files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".bncl")).forEach(this::refresh);
            scanned = true; // a failed scan stays unscanned: a wrong working directory must not be permanent
        } catch (IOException e) {
            LOGGER.warn("could not read {} for the '{}' pool - retrying on the next launch", dir.toAbsolutePath(), tag);
        }
    }

    /** What the type expects and the map has not got, or null where it is ready to play. */
    private @Nullable String wants(Arena arena) {
        GameType serving = type;
        if (serving == null) return null;
        for (Map.Entry<io.github.term4.barnacle.marker.MarkerKey<?>, Integer> expect : serving.expects().entrySet()) {
            int found = arena.markers(expect.getKey()).size();
            if (found != expect.getValue()) {
                return expect.getValue() + " " + expect.getKey().id() + " (it has " + found + ")";
            }
            // the count is not the whole contract: every limit in play is a height plane, and a rule that reads
            // one unpinned throws from module install - a map with the right count then fails EVERY launch
            for (io.github.term4.barnacle.marker.Marker marker : arena.markers(expect.getKey())) {
                if (marker instanceof io.github.term4.barnacle.marker.LimitMarker limit && limit.y() == null) {
                    return expect.getKey().id() + " with y pinned (/marker limit " + expect.getKey().id() + " y=<n>)";
                }
            }
        }
        for (Fitness check : checks) {
            String wants;
            try {
                wants = check.wants(arena);
            } catch (RuntimeException e) { // a check that throws is itself a map the mode cannot read
                wants = "a readable look (" + (e.getMessage() != null ? e.getMessage() : e.toString()) + ")";
            }
            if (wants != null) return wants;
        }
        return null;
    }

    private void refresh(Path file) {
        Arena arena;
        try {
            arena = Arena.read(file);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("unreadable map {} for the '{}' pool", file, tag, e);
            return;
        }
        if (!arena.meta().tags().contains(tag)) {
            unfinished.remove(stem(file));
            HotArena.drain(maps.remove(file));
            return;
        }
        String wants = wants(arena);
        if (wants != null) {
            // a match that drew this would die at launch, and the whole mode with it while the map is being built
            unfinished.put(stem(file), wants);
            HotArena.drain(maps.remove(file));
            LOGGER.warn("map {} is out of the '{}' rotation until it has {}", file.getFileName(), tag, wants);
            return;
        }
        unfinished.remove(stem(file));
        HotArena.drain(maps.put(file, arena));
        LOGGER.info("[maps:{}] {} in rotation", tag, file.getFileName());
    }
}
