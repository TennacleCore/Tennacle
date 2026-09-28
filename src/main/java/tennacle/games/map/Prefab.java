package tennacle.games.map;

import io.github.term4.barnacle.marker.RegionMarker;
import io.github.term4.grotto.Game;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Blocks authored in one arena and laid in another: read once as offsets from an anchor, stamped at any anchor. */
public final class Prefab {

    public record Cell(@NotNull BlockVec offset, @NotNull Block block) {}

    private Prefab() {}

    /** Every block but air in {@code box} of {@code source}, as offsets from {@code anchor}. */
    public static @NotNull List<Cell> read(@NotNull Instance source, @NotNull RegionMarker box, @NotNull Point anchor) {
        List<Cell> cells = new ArrayList<>();
        for (RegionMarker.Cuboid part : box.boxes()) {
            Chunks.load(source, part.min().blockX() >> 4, part.max().blockX() >> 4, part.min().blockZ() >> 4, part.max().blockZ() >> 4);
            for (int x = part.min().blockX(); x <= part.max().blockX(); x++) {
                for (int z = part.min().blockZ(); z <= part.max().blockZ(); z++) {
                    for (int y = part.min().blockY(); y <= part.max().blockY(); y++) {
                        Block block = source.getBlock(x, y, z);
                        if (block.air()) continue;
                        cells.add(new Cell(new BlockVec(x - anchor.blockX(), y - anchor.blockY(), z - anchor.blockZ()), block));
                    }
                }
            }
        }
        return cells;
    }

    /** {@code cells} into the game's world at {@code anchor}; every chunk they touch is loaded first. */
    public static void stamp(@NotNull Game game, @NotNull Point anchor, @NotNull List<Cell> cells) {
        if (cells.isEmpty()) return;
        int minCx = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE, minCz = Integer.MAX_VALUE, maxCz = Integer.MIN_VALUE;
        for (Cell cell : cells) {
            int cx = (anchor.blockX() + cell.offset().blockX()) >> 4, cz = (anchor.blockZ() + cell.offset().blockZ()) >> 4;
            minCx = Math.min(minCx, cx);
            maxCx = Math.max(maxCx, cx);
            minCz = Math.min(minCz, cz);
            maxCz = Math.max(maxCz, cz);
        }
        Chunks.load(game.instance(), minCx, maxCx, minCz, maxCz);
        for (Cell cell : cells) {
            game.shard().setBlock(new BlockVec(anchor.blockX() + cell.offset().blockX(), anchor.blockY() + cell.offset().blockY(),
                    anchor.blockZ() + cell.offset().blockZ()), cell.block());
        }
    }
}
