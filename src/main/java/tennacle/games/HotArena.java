package tennacle.games;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import net.minestom.server.timer.TaskSchedule;
import io.github.term4.barnacle.Arena;
import io.github.term4.bloom.Bloom;
import net.minestom.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A {@code .bncl}-backed arena: reads {@code arenas/<name>.bncl} lazily and hot-reloads on
 * {@code /arena save} - the NEXT game gets the edit, in-flight games finish on the old arena, and the stale
 * instance unregisters once its last game drains. A missing file never blocks boot (this is the server maps
 * are authored ON); launching before the map exists is the error.
 */
public final class HotArena implements Maps {

    private static final Logger LOGGER = LoggerFactory.getLogger(HotArena.class);

    private final String name;
    private volatile Arena arena;

    private HotArena(String name) {
        this.name = name;
    }

    public static HotArena of(String name) {
        HotArena hot = new HotArena(name);
        Bloom.onSave(saved -> {
            if (saved.getFileName().toString().equals(name + ".bncl")) hot.reload(saved);
        });
        return hot;
    }

    @Override
    public Arena get() {
        Arena loaded = arena;
        if (loaded != null) return loaded;
        Path file = tennacle.TestServer.arenaDir().resolve(name + ".bncl");
        try {
            arena = loaded = Arena.read(Files.readAllBytes(file));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(file + " missing or unreadable - build the map in Bloom and /arena save it", e);
        }
        return loaded;
    }

    @Override
    public @Nullable Arena named(@NotNull String map) {
        return map.equals(name) ? get() : null;
    }

    @Override
    public @NotNull Set<String> names() {
        return Set.of(name);
    }

    private void reload(Path file) {
        Arena old = arena;
        try {
            arena = Arena.read(Files.readAllBytes(file));
        } catch (IOException | RuntimeException e) {
            LOGGER.error("arena {} reload failed, keeping the old one", name, e);
            return;
        }
        LOGGER.info("arena {} reloaded - the next game uses your edit", name);
        drain(old); // first save may beat the first launch
    }

    private static final long DRAIN_CHECK_SECONDS = 30;
    private static final long DRAIN_DEADLINE_MINUTES = 60;

    /** Unregisters a replaced arena's instance once no game or player still stands in it. */
    static void drain(@org.jetbrains.annotations.Nullable Arena old) {
        var stale = old == null ? null : old.instantiated();
        if (stale == null) return;
        long giveUpAt = System.currentTimeMillis() + DRAIN_DEADLINE_MINUTES * 60_000L;
        // a save during a live match used to leak the instance for good: one check, then never again
        MinecraftServer.getSchedulerManager().submitTask(() -> {
            boolean inUse = !stale.getPlayers().isEmpty()
                    || Matches.grotto().games().stream().anyMatch(g -> g.instance() == stale);
            if (inUse && System.currentTimeMillis() < giveUpAt) return TaskSchedule.seconds(DRAIN_CHECK_SECONDS);
            if (!inUse) MinecraftServer.getInstanceManager().unregisterInstance(stale);
            else LOGGER.warn("a replaced arena was still in use after {}m - leaving it registered", DRAIN_DEADLINE_MINUTES);
            return TaskSchedule.stop();
        });
    }
}
