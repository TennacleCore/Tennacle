package tennacle.games.map;

import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Every chunk of a range loaded with ONE wait - a stamp mid-match must not stall the tick per cell. */
public final class Chunks {

    private Chunks() {}

    public static void load(@NotNull Instance instance, int minCx, int maxCx, int minCz, int maxCz) {
        List<CompletableFuture<?>> loads = new ArrayList<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (!instance.isChunkLoaded(cx, cz)) loads.add(instance.loadChunk(cx, cz));
            }
        }
        if (!loads.isEmpty()) CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).join();
    }
}
