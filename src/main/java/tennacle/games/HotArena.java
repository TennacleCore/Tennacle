package tennacle.games;

import io.github.term4.barnacle.Arena;
import io.github.term4.bloom.Bloom;
import net.minestom.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * A {@code .bncl}-backed arena: reads {@code arenas/<name>.bncl} and hot-reloads on {@code /arena save} -
 * the NEXT game gets the edit, in-flight games finish on the old arena, and the stale instance
 * unregisters once its last game drains. Maps are made (Bloom), never generated.
 */
public final class HotArena implements Supplier<Arena> {

    private final String name;
    private volatile Arena arena;

    private HotArena(String name, Arena initial) {
        this.name = name;
        this.arena = initial;
    }

    public static HotArena of(String name) {
        Path file = Path.of("arenas", name + ".bncl");
        Arena initial;
        try {
            initial = Arena.read(Files.readAllBytes(file));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(file + " missing or unreadable - build the map in Bloom and /arena save it", e);
        }
        HotArena hot = new HotArena(name, initial);
        Bloom.onSave(saved -> {
            if (saved.getFileName().toString().equals(name + ".bncl")) hot.reload(saved);
        });
        return hot;
    }

    @Override
    public Arena get() {
        return arena;
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
        var stale = old.instantiated();
        if (stale == null) return;
        MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
            boolean inUse = !stale.getPlayers().isEmpty()
                    || Duels.grotto().games().stream().anyMatch(g -> g.instance() == stale);
            if (!inUse) MinecraftServer.getInstanceManager().unregisterInstance(stale);
        });
    }
}
