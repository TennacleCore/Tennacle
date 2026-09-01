package tennacle.games;

import io.github.term4.barnacle.Arena;
import io.github.term4.bloom.Bloom;
import net.minestom.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * A {@code .bncl}-backed arena: reads {@code arenas/<name>.bncl} lazily and hot-reloads on
 * {@code /arena save} - the NEXT game gets the edit, in-flight games finish on the old arena, and the stale
 * instance unregisters once its last game drains. A missing file never blocks boot (this is the server maps
 * are authored ON); launching before the map exists is the error.
 */
public final class HotArena implements Supplier<Arena> {

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
        Path file = Path.of("arenas", name + ".bncl");
        try {
            arena = loaded = Arena.read(Files.readAllBytes(file));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(file + " missing or unreadable - build the map in Bloom and /arena save it", e);
        }
        return loaded;
    }

    private void reload(Path file) {
        Arena old = arena;
        try {
            arena = Arena.read(Files.readAllBytes(file));
        } catch (IOException | RuntimeException e) {
            System.err.println("[" + name + "] reload failed, keeping the old arena: " + e);
            return;
        }
        System.out.println("[" + name + "] arena reloaded - the next game uses your edit");
        drain(old); // first save may beat the first launch
    }

    /** Unregisters a replaced arena's instance once no game or player still stands in it. */
    static void drain(@org.jetbrains.annotations.Nullable Arena old) {
        var stale = old == null ? null : old.instantiated();
        if (stale == null) return;
        MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
            boolean inUse = !stale.getPlayers().isEmpty()
                    || Duels.grotto().games().stream().anyMatch(g -> g.instance() == stale);
            if (!inUse) MinecraftServer.getInstanceManager().unregisterInstance(stale);
        });
    }
}
